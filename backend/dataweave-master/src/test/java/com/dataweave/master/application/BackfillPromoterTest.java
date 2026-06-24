package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * backfill-parallelism-throttle：晋升器单元测试（Mockito）。
 *
 * <p>用真 {@link org.springframework.transaction.support.TransactionTemplate} 包一个 mock
 * {@link PlatformTransactionManager}，使 {@code execute} 回调真实执行；jdbc 全 mock，按 SQL 片段桩返回，
 * 验证「活跃 bizDate 全终态 → 晋升下一个 held」「达配额不超发」「全终态收敛 run.state」「sweep 兜底」。
 */
class BackfillPromoterTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-7000-8000-0000000000aa");

    private JdbcTemplate jdbc;
    private EventBus eventBus;
    private BackfillPromoter promoter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        eventBus = mock(EventBus.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        promoter = new BackfillPromoter(jdbc, eventBus, txManager);

        // 进行中批次列表（RowMapper 重载）。
        when(jdbc.query(contains("FROM backfill_run WHERE state='RUNNING'"), any(RowMapper.class)))
                .thenReturn(List.of(RUN));
        // per-run 行锁取 parallelism（ResultSetExtractor 重载）。
        when(jdbc.query(contains("FOR UPDATE"), any(ResultSetExtractor.class), any())).thenReturn(2);
    }

    @SuppressWarnings("unchecked")
    private void stubMinHeldDate(String date) {
        when(jdbc.query(contains("MIN(biz_date)"), any(ResultSetExtractor.class), any())).thenReturn(date);
    }

    private void stubCounts(int active, int held) {
        when(jdbc.queryForObject(contains("COALESCE(backfill_held,0)=0"), eq(Integer.class), any())).thenReturn(active);
        when(jdbc.queryForObject(contains("COALESCE(backfill_held,0)=1"), eq(Integer.class), any())).thenReturn(held);
    }

    @Test
    void promotesNextHeldDateWhenCapacityFree() {
        // par=2，一个活跃 bizDate 已终态（active=1），还有 2 个 held → 晋升 1 个补足配额。
        stubCounts(1, 2);
        stubMinHeldDate("2026-06-22");
        when(jdbc.update(contains("SET backfill_held=0"), any(), any(), any())).thenReturn(1);

        promoter.promoteEligible();

        // 恰好晋升一次（active 1→2 达配额即停），并补发 WAKE 触发认领。
        verify(jdbc, times(1)).update(contains("SET backfill_held=0"), any(), eq(RUN), eq("2026-06-22"));
        verify(eventBus).publish(eq(InstanceStates.WAKE_CHANNEL), anyString());
    }

    @Test
    void doesNotPromoteWhenAtCapacity() {
        // 严格 N（6.4）：active 已达 parallelism → 不晋升、不发 WAKE（per-run 行锁 + 此处达配额即上限）。
        stubCounts(2, 2);

        promoter.promoteEligible();

        verify(jdbc, never()).update(contains("SET backfill_held=0"), any(), any(), any());
        verify(eventBus, never()).publish(anyString(), anyString());
    }

    @Test
    void convergesRunStateWhenAllTerminal() {
        // 收敛（6.5）：无活跃、无持有 → 全终态 → 落 run.state。total=2/success=2/failed=0 → SUCCESS。
        stubCounts(0, 0);
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any())).thenReturn(2);          // total
        when(jdbc.queryForObject(contains("state='SUCCESS'"), eq(Integer.class), any())).thenReturn(2);
        when(jdbc.queryForObject(contains("state='FAILED'"), eq(Integer.class), any())).thenReturn(0);

        promoter.promoteEligible();

        verify(jdbc).update(contains("UPDATE backfill_run SET state=?"), eq("SUCCESS"), any(), eq(RUN));
        verify(jdbc, never()).update(contains("SET backfill_held=0"), any(), any(), any());
    }

    @Test
    void sweepAlsoPromotes() {
        // sweep 兜底（6.8）：无 WAKE 也能补晋升（与 promoteEligible 同逻辑）。
        stubCounts(1, 2);
        stubMinHeldDate("2026-06-22");
        when(jdbc.update(contains("SET backfill_held=0"), any(), any(), any())).thenReturn(1);

        promoter.sweep();

        verify(jdbc, times(1)).update(contains("SET backfill_held=0"), any(), eq(RUN), eq("2026-06-22"));
        verify(eventBus).publish(eq(InstanceStates.WAKE_CHANNEL), anyString());
    }
}
