package com.dataweave.api.infrastructure;

import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * distributed 下发寻址：gateway 从 worker_nodes 注册表按 nodeCode 取可达 host:port 拼 exec URL，
 * 支持本机多 worker（不同端口）。host 未含端口或节点缺失时回退到旧行为（nodeCode:8100）。
 */
@ExtendWith(MockitoExtension.class)
class DistributedTaskExecutionGatewayTest {

    @Mock
    private WorkerNodeRepository nodeRepository;

    private DistributedTaskExecutionGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new DistributedTaskExecutionGateway(
                WebClient.builder(), "tok", "http", nodeRepository);
    }

    private WorkerNode node(String code, String host) {
        WorkerNode n = new WorkerNode();
        n.setNodeCode(code);
        n.setHost(host);
        return n;
    }

    @Test
    void registeredHostWithPort_isUsedVerbatim() {
        when(nodeRepository.findByNodeCode("worker-1"))
                .thenReturn(Optional.of(node("worker-1", "127.0.0.1:8100")));

        assertThat(gateway.resolveWorkerUrl("worker-1"))
                .isEqualTo("http://127.0.0.1:8100/internal/worker/exec");
    }

    @Test
    void registeredHostWithPort_distinctPortsPerWorker() {
        when(nodeRepository.findByNodeCode("worker-2"))
                .thenReturn(Optional.of(node("worker-2", "127.0.0.1:8101")));

        assertThat(gateway.resolveWorkerUrl("worker-2"))
                .isEqualTo("http://127.0.0.1:8101/internal/worker/exec");
    }

    @Test
    void registeredHostWithoutPort_fallsBackToDefaultPort() {
        when(nodeRepository.findByNodeCode("worker-1"))
                .thenReturn(Optional.of(node("worker-1", "some-host")));

        assertThat(gateway.resolveWorkerUrl("worker-1"))
                .isEqualTo("http://some-host:8100/internal/worker/exec");
    }

    @Test
    void nodeMissing_fallsBackToNodeCodeAsHost() {
        when(nodeRepository.findByNodeCode("worker-9")).thenReturn(Optional.empty());

        assertThat(gateway.resolveWorkerUrl("worker-9"))
                .isEqualTo("http://worker-9:8100/internal/worker/exec");
    }

    @Test
    void nodeWithNullHost_fallsBackToNodeCodeAsHost() {
        when(nodeRepository.findByNodeCode("worker-1"))
                .thenReturn(Optional.of(node("worker-1", null)));

        assertThat(gateway.resolveWorkerUrl("worker-1"))
                .isEqualTo("http://worker-1:8100/internal/worker/exec");
    }
}
