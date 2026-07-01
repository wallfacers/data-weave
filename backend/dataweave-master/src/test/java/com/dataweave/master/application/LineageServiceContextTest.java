package com.dataweave.master.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.AtomicMetricRepository;
import com.dataweave.master.lineage.MetricLineage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

/**
 * 036 FR-013：LineageService 必须以上下文 (tenantId, projectId) 查询，移除硬编码 1L/1L。
 *
 * <p>纯单测：mock {@link AtomicMetricRepository} + {@link LineageQueryService}，断言：
 * ① 指标定义走项目作用域查询 {@code findFirstByProjectIdAndCodeOrderByVersionNoDesc}；
 * ② neo4j 查询拿到的是调用方传入的 tenantId/projectId（任意非 1 值），而非常量 1L/1L。
 */
class LineageServiceContextTest {

    @Test
    void lineageOf_以上下文tenantIdProjectId查询_不硬编码1L() {
        AtomicMetricRepository repo = mock(AtomicMetricRepository.class);
        LineageQueryService queryService = mock(LineageQueryService.class);
        LineageService service = new LineageService(repo, queryService);

        AtomicMetric metric = new AtomicMetric();
        metric.setId(4242L);
        metric.setCode("GMV");
        metric.setProjectId(9L);
        // 项目作用域查定义：projectId=9, code=GMV
        when(repo.findFirstByProjectIdAndCodeOrderByVersionNoDesc(9L, "GMV"))
                .thenReturn(Optional.of(metric));
        MetricLineage stub = new MetricLineage(null, java.util.List.of(), java.util.List.of());
        when(queryService.metricLineage(anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(stub);

        // 传入非 1 的上下文值（7L/9L），若硬编码 1L/1L 则捕获不到这些值
        service.lineageOf(7L, 9L, "GMV");

        // ① 定义查询用项目作用域（projectId=9），而非跨项目 findFirstByCode
        verify(repo).findFirstByProjectIdAndCodeOrderByVersionNoDesc(9L, "GMV");
        verify(repo, never()).findFirstByCodeOrderByVersionNoDesc(anyString());

        // ② neo4j 查询拿到的 tenantId/projectId == 上下文（7L/9L），不是 1L/1L
        ArgumentCaptor<Long> tenantCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> projectCaptor = ArgumentCaptor.forClass(Long.class);
        verify(queryService).metricLineage(tenantCaptor.capture(), projectCaptor.capture(),
                eq("ATOMIC"), eq(4242L));
        assertThat(tenantCaptor.getValue()).isEqualTo(7L);
        assertThat(projectCaptor.getValue()).isEqualTo(9L);
    }

    @Test
    void lineageOf_项目内无该指标定义_返回empty且不查neo4j() {
        AtomicMetricRepository repo = mock(AtomicMetricRepository.class);
        LineageQueryService queryService = mock(LineageQueryService.class);
        LineageService service = new LineageService(repo, queryService);

        when(repo.findFirstByProjectIdAndCodeOrderByVersionNoDesc(9L, "NOPE"))
                .thenReturn(Optional.empty());

        assertThat(service.lineageOf(7L, 9L, "NOPE")).isEmpty();
        verify(queryService, never()).metricLineage(anyLong(), anyLong(), anyString(), anyLong());
    }
}
