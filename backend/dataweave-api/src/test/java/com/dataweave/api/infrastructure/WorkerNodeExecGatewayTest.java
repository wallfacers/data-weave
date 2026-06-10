package com.dataweave.api.infrastructure;

import com.dataweave.master.application.FleetService;
import com.dataweave.master.application.NodeExecGateway.ExecResult;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.worker.application.ControlledCommandExecutor;
import com.dataweave.worker.application.ControlledCommandExecutor.CommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * master 侧派发：节点在线校验 + 转发；离线/不存在节点报错不派发。
 */
@ExtendWith(MockitoExtension.class)
class WorkerNodeExecGatewayTest {

    @Mock
    private FleetService fleetService;
    @Mock
    private ControlledCommandExecutor executor;

    private WorkerNodeExecGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new WorkerNodeExecGateway(fleetService, executor);
    }

    private WorkerNode node(String code, String status) {
        WorkerNode n = new WorkerNode();
        n.setNodeCode(code);
        n.setStatus(status);
        return n;
    }

    @Test
    void offlineNode_returnsErrorNoDispatch() {
        when(fleetService.node("node-3")).thenReturn(Optional.of(node("node-3", "OFFLINE")));
        ExecResult r = gateway.exec("node-3", "df -h");
        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("离线");
        verify(executor, never()).execute(any());
    }

    @Test
    void missingNode_returnsErrorNoDispatch() {
        when(fleetService.node("ghost")).thenReturn(Optional.empty());
        ExecResult r = gateway.exec("ghost", "df -h");
        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("不存在");
        verify(executor, never()).execute(any());
    }

    @Test
    void onlineNode_dispatchesAndMapsResult() {
        when(fleetService.node("node-1")).thenReturn(Optional.of(node("node-1", "ONLINE")));
        when(executor.execute("df -h")).thenReturn(
                new CommandResult(true, 0, "Filesystem ...", "", false, false, 14, "执行完成"));

        ExecResult r = gateway.exec("node-1", "df -h");
        assertThat(r.success()).isTrue();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).contains("Filesystem");
        verify(executor).execute("df -h");
    }
}
