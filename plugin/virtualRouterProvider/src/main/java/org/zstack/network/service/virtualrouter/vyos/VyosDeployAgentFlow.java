package org.zstack.network.service.virtualrouter.vyos;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.appliancevm.*;
import org.zstack.appliancevm.ApplianceVmConstant.Params;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.ansible.AnsibleFacade;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.thread.CancelablePeriodicTask;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.header.core.workflow.FlowTrigger;
import org.zstack.header.core.workflow.NoRollbackFlow;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.vm.VmInstanceConstant;
import org.zstack.header.vm.VmInstanceConstant.VmOperation;
import org.zstack.header.vm.VmInstanceSpec;
import org.zstack.header.vm.VmNicInventory;
import org.zstack.network.service.virtualrouter.VirtualRouterGlobalConfig;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.network.NetworkUtils;
import org.zstack.utils.path.PathUtil;
import org.zstack.utils.ssh.Ssh;
import org.zstack.utils.ssh.SshException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.zstack.core.Platform.err;
import static org.zstack.core.Platform.operr;

/**
 * Created by xing5 on 2016/10/31.
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class VyosDeployAgentFlow extends NoRollbackFlow {
    private static final CLogger logger = Utils.getLogger(VyosDeployAgentFlow.class);

    @Autowired
    private AnsibleFacade asf;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private ThreadFacade thdf;

    @Override
    public void run(FlowTrigger trigger, Map data) {
        if (CoreGlobalProperty.UNIT_TEST_ON) {
            trigger.next();
            return;
        }

        boolean isReconnect = Boolean.parseBoolean((String) data.get(Params.isReconnect.toString()));

        if (!isReconnect && !ApplianceVmGlobalConfig.DEPLOY_AGENT_ON_START.value(Boolean.class)) {
            // no need to deploy agent
            trigger.next();
            return;
        }

        String mgmtNicIp;
        if (!isReconnect) {
            VmNicInventory mgmtNic;
            final VmInstanceSpec spec = (VmInstanceSpec) data.get(VmInstanceConstant.Params.VmInstanceSpec.toString());
            if (spec.getCurrentVmOperation() == VmOperation.NewCreate) {
                final ApplianceVmSpec aspec = spec.getExtensionData(ApplianceVmConstant.Params.applianceVmSpec.toString(), ApplianceVmSpec.class);
                mgmtNic = CollectionUtils.find(spec.getDestNics(), new Function<VmNicInventory, VmNicInventory>() {
                    @Override
                    public VmNicInventory call(VmNicInventory arg) {
                        return arg.getL3NetworkUuid().equals(aspec.getManagementNic().getL3NetworkUuid()) ? arg : null;
                    }
                });
            } else {
                ApplianceVmVO avo = dbf.findByUuid(spec.getVmInventory().getUuid(), ApplianceVmVO.class);
                ApplianceVmInventory ainv = ApplianceVmInventory.valueOf(avo);
                mgmtNic = ainv.getManagementNic();
            }
            mgmtNicIp = mgmtNic.getIp();
        } else {
            mgmtNicIp = (String) data.get(Params.managementNicIp.toString());
        }

        int timeoutInSeconds = ApplianceVmGlobalConfig.CONNECT_TIMEOUT.value(Integer.class);
        long timeout = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutInSeconds);

        List<Throwable> errors = new ArrayList<>();
        thdf.submitCancelablePeriodicTask(new CancelablePeriodicTask() {
            @Override
            public boolean run() {
                try {
                    long now = System.currentTimeMillis();
                    if (now > timeout) {
                        trigger.fail(err(ApplianceVmErrors.UNABLE_TO_START, "vyos deploy agent failed, because %s",
                                errors.get(errors.size() -1)));
                        return true;
                    }

                    if (NetworkUtils.isRemotePortOpen(mgmtNicIp, 22, 2000)) {
                        deployAgent();
                        return true;
                    } else {
                        return false;
                    }
                } catch (Throwable t) {
                    logger.warn("vyos deploy agent failed", t);
                    errors.add(t);
                    return false;
                }
            }

            private void deployAgent() {
                String script = "sudo bash /home/vyos/zvrboot.bin\n" +
                        "sudo bash /home/vyos/zvr.bin\n" +
                        "sudo bash /etc/init.d/zstack-virtualrouteragent restart\n";

                try {
                    new Ssh().setTimeout(300).scpUpload(
                            PathUtil.findFileOnClassPath("ansible/zvr/zvr.bin", true).getAbsolutePath(),
                            "/home/vyos/zvr.bin"
                    ).scpUpload(
                            PathUtil.findFileOnClassPath("ansible/zvr/zvrboot.bin", true).getAbsolutePath(),
                            "/home/vyos/zvrboot.bin"
                    ).scpUpload(
                            PathUtil.findFileOnClassPath("ansible/zvr/version", true).getAbsolutePath(),
                            "/home/vyos/zvr/version"
                    ).setPrivateKey(asf.getPrivateKey()).setUsername("vyos").setHostname(mgmtNicIp).setPort(22).runErrorByExceptionAndClose();

                    new Ssh().shell(script
                    ).setTimeout(300).setPrivateKey(asf.getPrivateKey()).setUsername("vyos").setHostname(mgmtNicIp).setPort(22).runErrorByExceptionAndClose();
                } catch (SshException  e ) {
                    /*
                    ZSTAC-18352, try again with password when key fail
                     */
                    String password = VirtualRouterGlobalConfig.VYOS_PASSWORD.value();
                    new Ssh().setTimeout(300).scpUpload(
                            PathUtil.findFileOnClassPath("ansible/zvr/zvr.bin", true).getAbsolutePath(),
                            "/home/vyos/zvr.bin"
                    ).scpUpload(
                            PathUtil.findFileOnClassPath("ansible/zvr/zvrboot.bin", true).getAbsolutePath(),
                            "/home/vyos/zvrboot.bin"
                    ).scpUpload(
                            PathUtil.findFileOnClassPath("ansible/zvr/version", true).getAbsolutePath(),
                            "/home/vyos/zvr/version"
                    ).setPassword(password).setUsername("vyos").setHostname(mgmtNicIp).setPort(22).runErrorByExceptionAndClose();

                    new Ssh().shell(script
                    ).setTimeout(300).setPassword(password).setUsername("vyos").setHostname(mgmtNicIp).setPort(22).runErrorByExceptionAndClose();


                }
                trigger.next();
            }

            @Override
            public TimeUnit getTimeUnit() {
                return TimeUnit.SECONDS;
            }

            @Override
            public long getInterval() {
                /* retry too fast will produce too much useless log */
                return 20;
            }

            @Override
            public String getName() {
                return VyosDeployAgentFlow.class.getName();
            }
        });
    }
}
