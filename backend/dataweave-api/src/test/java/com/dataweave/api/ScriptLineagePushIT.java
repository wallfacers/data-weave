package com.dataweave.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ProjectSyncDtos;
import com.dataweave.master.application.ProjectSyncService;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.LineageStore;
import com.dataweave.master.domain.lineage.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 041 T007：push 触发脚本血缘 + 零阻断（FR-001/FR-005/FR-011）。
 *
 * <p>H2 内存库 + mock LineageStore（捕获 recordTaskIo 入参断言 SCRIPT_SQL 边与坐标）。
 * BEFORE_CLASS 重建上下文隔离前序 mutator（backend 测试隔离不变量）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ScriptLineagePushIT {

    @Autowired
    ProjectSyncService syncService;

    @MockitoBean
    LineageStore lineageStore;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, 1L, "admin");
    }

    private ProjectSyncDtos.PushResult pushWith(Map<String, String> extraFiles) {
        ProjectSyncDtos.PullResult before = syncService.pull(1L, 1L);
        Map<String, String> files = new HashMap<>(before.bundle().files());
        files.putAll(extraFiles);
        ProjectSyncDtos.PushCommand cmd = new ProjectSyncDtos.PushCommand(
                files, before.baseline(), false, files.size(), "041 script lineage IT");
        return syncService.push(1L, 1L, 1L, cmd);
    }

    @Test
    void pushPythonTaskWithEmbeddedSqlRecordsScriptSqlEdges() {
        pushWith(Map.of(
                "pyjob41.task.yaml", "formatVersion: 1\nname: pyjob41\ntype: PYTHON\n",
                "pyjob41.py", "sql = \"INSERT INTO dw.orders41 SELECT id, amount FROM ods.orders41\"\nspark.sql(sql)\n"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IoEdge>> captor = ArgumentCaptor.forClass(List.class);
        verify(lineageStore, atLeastOnce()).recordTaskIo(anyLong(), anyLong(), anyLong(),
                any(), any(), captor.capture(), any(), any());

        List<IoEdge> all = captor.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(all).anyMatch(e -> e.source() == Source.SCRIPT_SQL
                && e.direction() == Direction.READS
                && e.table().qualifiedName().equalsIgnoreCase("ods.orders41"));
        assertThat(all).anyMatch(e -> e.source() == Source.SCRIPT_SQL
                && e.direction() == Direction.WRITES
                && e.table().qualifiedName().equalsIgnoreCase("dw.orders41"));
        // FR-011：任务未绑定数据源 → 租户级降级身份（dsKey = tenantId|datasource:）
        assertThat(all).allMatch(e -> e.table().datasource().tenantId() == 1L);
    }

    @Test
    void pushShellTaskWithHiveCarrierRecordsEdges() {
        pushWith(Map.of(
                "shjob41.task.yaml", "formatVersion: 1\nname: shjob41\ntype: SHELL\n",
                "shjob41.sh", "hive -e \"INSERT INTO dw.sh41 SELECT k FROM ods.sh41\"\n"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IoEdge>> captor = ArgumentCaptor.forClass(List.class);
        verify(lineageStore, atLeastOnce()).recordTaskIo(anyLong(), anyLong(), anyLong(),
                any(), any(), captor.capture(), any(), any());
        List<IoEdge> all = captor.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(all).anyMatch(e -> e.source() == Source.SCRIPT_SQL
                && e.table().qualifiedName().equalsIgnoreCase("dw.sh41"));
    }

    @Test
    void lineageStoreFailureDoesNotBlockPush() {
        doThrow(new IllegalStateException("neo4j down")).when(lineageStore)
                .recordTaskIo(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any());
        ProjectSyncDtos.PushResult result = pushWith(Map.of(
                "pyjob42.task.yaml", "formatVersion: 1\nname: pyjob42\ntype: PYTHON\n",
                "pyjob42.py", "spark.sql(\"INSERT INTO dw.t42 SELECT 1\")\n"));
        assertThat(result.newBaseline()).isNotBlank();
    }

    @Test
    void syntaxErrorScriptStillPushes() {
        ProjectSyncDtos.PushResult result = pushWith(Map.of(
                "pyjob43.task.yaml", "formatVersion: 1\nname: pyjob43\ntype: PYTHON\n",
                "pyjob43.py", "def broken(:\n  'unclosed\n"));
        assertThat(result.newBaseline()).isNotBlank();
    }
}
