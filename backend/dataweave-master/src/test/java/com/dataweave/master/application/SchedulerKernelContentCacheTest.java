package com.dataweave.master.application;

import com.dataweave.master.domain.EventBus;
import com.dataweave.master.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 收尾修复 {@code SchedulerKernel.contentOf/paramsJsonOf} 缓存策略:
 * 046 曾把草稿键({@code taskId:draft})一并缓存且永不失效——TEST 跑草稿(D9)/未发布版本的
 * NORMAL 节点在编辑或 push 后仍跑旧内容。修复后:仅版本键缓存(版本冻结不可变),草稿每次落库读。
 */
class SchedulerKernelContentCacheTest {

    private JdbcTemplate jdbc;
    private SchedulerKernel kernel;

    @BeforeEach
    void setUp() {
        DataSource ds = new SingleConnectionDataSource("jdbc:h2:mem:content_cache_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", true);
        jdbc = new JdbcTemplate(ds);
        try { jdbc.execute("DROP ALL OBJECTS"); } catch (Exception ignored) {}
        jdbc.execute("CREATE TABLE task_def (id BIGINT PRIMARY KEY, content VARCHAR(4000), params_json VARCHAR(4000))");
        jdbc.execute("CREATE TABLE task_def_version (task_id BIGINT, version_no INT, content VARCHAR(4000), params_json VARCHAR(4000))");
        kernel = new SchedulerKernel(jdbc,
                mock(InstanceStateMachine.class), mock(SlotManager.class),
                mock(SchedulingPolicy.class), mock(TaskExecutionGateway.class),
                mock(EventBus.class), mock(PreemptionService.class),
                mock(SchedulerMetrics.class), mock(ParallelDispatcher.class),
                mock(ScheduleParamResolver.class), mock(Messages.class),
                mock(PlatformTransactionManager.class), 50, 120, 200, 5);
    }

    private String contentOf(Long taskId, Integer versionNo, String override) throws Exception {
        SchedulerKernel.Row r = new SchedulerKernel.Row();
        r.taskId = taskId;
        r.taskVersionNo = versionNo;
        r.contentOverride = override;
        Method m = SchedulerKernel.class.getDeclaredMethod("contentOf", SchedulerKernel.Row.class);
        m.setAccessible(true);
        return (String) m.invoke(kernel, r);
    }

    private String paramsJsonOf(Long taskId, Integer versionNo) throws Exception {
        SchedulerKernel.Row r = new SchedulerKernel.Row();
        r.taskId = taskId;
        r.taskVersionNo = versionNo;
        Method m = SchedulerKernel.class.getDeclaredMethod("paramsJsonOf", SchedulerKernel.Row.class);
        m.setAccessible(true);
        return (String) m.invoke(kernel, r);
    }

    @Test
    void draftContent_notCached_editVisibleImmediately() throws Exception {
        jdbc.update("INSERT INTO task_def (id, content) VALUES (1, 'echo old-draft')");
        assertThat(contentOf(1L, null, null)).isEqualTo("echo old-draft");
        // 模拟编辑草稿/project_push 更新 task_def.content
        jdbc.update("UPDATE task_def SET content='echo new-draft' WHERE id=1");
        assertThat(contentOf(1L, null, null)).isEqualTo("echo new-draft");  // 046 回归修复:不再命中旧缓存
    }

    @Test
    void versionedContent_cached_frozenSnapshotStable() throws Exception {
        jdbc.update("INSERT INTO task_def_version (task_id, version_no, content) VALUES (1, 3, 'echo v3')");
        assertThat(contentOf(1L, 3, null)).isEqualTo("echo v3");
        // 版本冻结不可变——即使底层被(不合规地)改写,缓存语义保持快照稳定
        jdbc.update("UPDATE task_def_version SET content='tampered' WHERE task_id=1 AND version_no=3");
        assertThat(contentOf(1L, 3, null)).isEqualTo("echo v3");  // 命中版本缓存
    }

    @Test
    void contentOverride_takesPriority_neverTouchesCache() throws Exception {
        jdbc.update("INSERT INTO task_def (id, content) VALUES (2, 'echo draft')");
        assertThat(contentOf(2L, null, "echo editor-latest")).isEqualTo("echo editor-latest");
    }

    @Test
    void draftParams_notCached_editVisibleImmediately() throws Exception {
        jdbc.update("INSERT INTO task_def (id, params_json) VALUES (3, '{\"k\":\"old\"}')");
        assertThat(paramsJsonOf(3L, null)).isEqualTo("{\"k\":\"old\"}");
        jdbc.update("UPDATE task_def SET params_json='{\"k\":\"new\"}' WHERE id=3");
        assertThat(paramsJsonOf(3L, null)).isEqualTo("{\"k\":\"new\"}");
    }
}
