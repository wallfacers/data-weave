package com.dataweave.master.application;

import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.domain.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FleetServiceTest {

    @Mock
    private WorkerNodeRepository repository;

    private FleetService fleetService;

    @BeforeEach
    void setUp() {
        fleetService = new FleetService(repository);
    }

    @Test
    void report_newNode_shouldSaveAsOnline() {
        when(repository.findByNodeCode("node-1")).thenReturn(Optional.empty());
        when(repository.save(any(WorkerNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerNode result = fleetService.report("node-1", "host-1", "4C/8G",
                0.3, 0.45, 0.5, 1.2, 0);

        assertThat(result.getStatus()).isEqualTo("ONLINE");
        assertThat(result.getNodeCode()).isEqualTo("node-1");
        assertThat(result.getHost()).isEqualTo("host-1");
        assertThat(result.getCapacity()).isEqualTo("4C/8G");
        assertThat(result.getCpu()).isEqualTo(0.3);
        assertThat(result.getMem()).isEqualTo(0.45);
        assertThat(result.getDisk()).isEqualTo(0.5);
        assertThat(result.getLoadAvg()).isEqualTo(1.2);
        assertThat(result.getRunningTasks()).isEqualTo(0);
        assertThat(result.getLastHeartbeat()).isNotNull();
        verify(repository).save(any(WorkerNode.class));
    }

    @Test
    void report_existingNode_shouldUpsertNotCreate() {
        WorkerNode existing = new WorkerNode();
        existing.setId(1L);
        existing.setNodeCode("node-1");
        existing.setCreatedAt(LocalDateTime.now().minusDays(1));
        existing.setDeleted(0);
        existing.setVersion(0);

        when(repository.findByNodeCode("node-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(WorkerNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkerNode result = fleetService.report("node-1", "host-1-updated", "8C/16G",
                0.6, 0.7, 0.8, 2.5, 3);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo("ONLINE");
        assertThat(result.getHost()).isEqualTo("host-1-updated");
        assertThat(result.getCapacity()).isEqualTo("8C/16G");
        assertThat(result.getCpu()).isEqualTo(0.6);
        assertThat(result.getRunningTasks()).isEqualTo(3);
        verify(repository).save(any(WorkerNode.class));
    }

    @Test
    void markStaleOffline_shouldMarkOnlyStaleNodes() {
        WorkerNode staleNode = new WorkerNode();
        staleNode.setId(1L);
        staleNode.setNodeCode("stale-node");
        staleNode.setStatus("ONLINE");
        staleNode.setLastHeartbeat(LocalDateTime.now().minus(Duration.ofSeconds(60)));

        WorkerNode freshNode = new WorkerNode();
        freshNode.setId(2L);
        freshNode.setNodeCode("fresh-node");
        freshNode.setStatus("ONLINE");
        freshNode.setLastHeartbeat(LocalDateTime.now());

        when(repository.findByStatus("ONLINE")).thenReturn(List.of(staleNode, freshNode));
        when(repository.save(any(WorkerNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int marked = fleetService.markStaleOffline();

        assertThat(marked).isEqualTo(1);
        assertThat(staleNode.getStatus()).isEqualTo("OFFLINE");
        assertThat(freshNode.getStatus()).isEqualTo("ONLINE");
        verify(repository).save(staleNode);
        verify(repository, never()).save(freshNode);
    }
}
