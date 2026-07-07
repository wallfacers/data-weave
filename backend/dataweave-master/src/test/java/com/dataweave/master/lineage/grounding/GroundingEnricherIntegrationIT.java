package com.dataweave.master.lineage.grounding;

import com.dataweave.master.application.DatasourceSchemaResolver;
import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.LineageEdgeAssembler;
import com.dataweave.master.application.lineage.TableSchema;
import com.dataweave.master.application.lineage.grounding.CatalogGroundingService;
import com.dataweave.master.application.lineage.grounding.SystemNamespaceClassifier;
import com.dataweave.master.application.lineage.grounding.TableExistence;
import com.dataweave.master.application.lineage.script.ScriptLineageService;
import com.dataweave.master.application.lineage.agent.AgentLineageConfigService;
import com.dataweave.master.application.lineage.agent.LineageAgentEnricher;
import com.dataweave.master.application.lineage.agent.LineageAgentEnrichmentRequested;
import com.dataweave.master.application.lineage.agent.LlmAgentClient;
import com.dataweave.master.application.lineage.DatasourceBoundCatalog;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.LineageStore;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import com.dataweave.master.domain.lineage.Transform;
import com.dataweave.master.infrastructure.InMemoryEventBus;
import com.dataweave.master.infrastructure.lineage.GroundingDispositionRepository;
import com.dataweave.master.infrastructure.lineage.Neo4jColumnBackfillWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T011：目录接地异步端到端集成测试（US1，契约 grounding-stage C1 + FR-008/FR-011/FR-014/SC-004）。
 *
 * <p>用<b>真</b> {@link LineageAgentEnricher} + 真 {@link InMemoryEventBus}（发布→订阅→有界池→handle→enrich）
 * + 真 {@link CatalogGroundingService} + 真 {@link DatasourceBoundCatalog}，只把最外围接缝打桩
 * （taskDef 仓储、assembler、脚本通道、AI 配置、lineageStore、schemaResolver.probeTable 三态、
 * neo4j 列目录、处置审计仓储）。证明：
 * <ul>
 *   <li>push 事件→异步接地真正触发：PRESENT 升 CONFIRMED、ABSENT 推断类剔除、列级连带剔除、处置落审计；</li>
 *   <li><b>AI 关闭仍接地</b>（config 缺席，仅靠 datasource-bound + kill-switch）；</li>
 *   <li><b>SC-004 故障注入</b>：grounding 阶段抛异常 → recordTaskIo 仍写既有（未接地）边集、无异常逃逸；</li>
 *   <li><b>等价性</b>：解绑数据源 / kill-switch 关闭 → 异步不做任何 recordTaskIo（与接地前行为一致）。</li>
 * </ul>
 */
class GroundingEnricherIntegrationIT {

    private static final long TENANT = 1L;
    private static final long PROJECT = 1L;
    private static final long TASK_ID = 100L;
    private static final long DS_ID = 10L;

    private InMemoryEventBus eventBus;
    private ObjectMapper objectMapper;
    private TaskDefRepository taskDefRepository;
    private LineageEdgeAssembler assembler;
    private ScriptLineageService scriptLineageService;
    private AgentLineageConfigService configService;
    private com.dataweave.master.infrastructure.lineage.AgentConfigRepository agentConfigRepository;
    private LineageStore lineageStore;
    private LlmAgentClient llmClient;
    private Neo4jColumnBackfillWriter backfillWriter;
    private ColumnLineageCatalog neo4jCatalog;
    private DatasourceRepository datasourceRepository;
    private GroundingDispositionRepository dispositionRepository;

    /** probeTable 三态桩：按 qualifiedName 返回既定裁决。 */
    private DatasourceSchemaResolver probingResolver(java.util.Map<String, TableExistence> verdicts) {
        return new DatasourceSchemaResolver(null, null, null, null, null) {
            @Override
            public TableExistence probeTable(long datasourceId, String qualifiedName) {
                return verdicts.getOrDefault(qualifiedName.toLowerCase(), TableExistence.UNKNOWN);
            }
        };
    }

    @BeforeEach
    void setUp() {
        DatasourceBoundCatalog.clearCache();
        eventBus = new InMemoryEventBus();
        objectMapper = new ObjectMapper();
        taskDefRepository = mock(TaskDefRepository.class);
        assembler = mock(LineageEdgeAssembler.class);
        scriptLineageService = mock(ScriptLineageService.class);
        configService = mock(AgentLineageConfigService.class);
        agentConfigRepository = mock(com.dataweave.master.infrastructure.lineage.AgentConfigRepository.class);
        lineageStore = mock(LineageStore.class);
        llmClient = mock(LlmAgentClient.class);
        backfillWriter = mock(Neo4jColumnBackfillWriter.class);
        neo4jCatalog = mock(ColumnLineageCatalog.class);
        datasourceRepository = mock(DatasourceRepository.class);
        dispositionRepository = mock(GroundingDispositionRepository.class);

        // AI 通道恒关闭（本 IT 只证 grounding 独立于 AI）
        when(configService.getActive(anyLong(), anyLong())).thenReturn(Optional.empty());
        // assembler：确定性 assembly 为空（候选边由脚本通道桩提供），坐标解析可空
        when(assembler.assemble(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LineageEdgeAssembler.Assembly(List.of()));
        // neo4j 列目录恒 miss → probeExistence 落到 schemaResolver.probeTable
        when(neo4jCatalog.lookupTable(anyLong(), anyLong(), anyString())).thenReturn(Optional.<TableSchema>empty());
        // engineOf → POSTGRES
        Datasource ds = new Datasource();
        ds.setId(DS_ID);
        ds.setTypeCode("POSTGRES");
        ds.setDeleted(0);
        when(datasourceRepository.findById(anyLong())).thenReturn(Optional.of(ds));
    }

    @AfterEach
    void tearDown() {
        DatasourceBoundCatalog.clearCache();
    }

    // ── 构造 + 订阅（反射调 package-private subscribe，模拟 @PostConstruct）──

    private LineageAgentEnricher newEnricher(CatalogGroundingService groundingService,
                                             DatasourceSchemaResolver schemaResolver,
                                             boolean groundingEnabled) throws Exception {
        LineageAgentEnricher enricher = new LineageAgentEnricher(
                eventBus, objectMapper, taskDefRepository, assembler, scriptLineageService,
                configService, agentConfigRepository, lineageStore, llmClient,
                schemaResolver, backfillWriter, neo4jCatalog, datasourceRepository,
                groundingService, dispositionRepository, groundingEnabled, 1);
        Method subscribe = LineageAgentEnricher.class.getDeclaredMethod("subscribe");
        subscribe.setAccessible(true);
        subscribe.invoke(enricher);
        return enricher;
    }

    private CatalogGroundingService realGrounding() {
        return new CatalogGroundingService(new SystemNamespaceClassifier(""));
    }

    private TaskDef task(Long datasourceId, Long targetDatasourceId) {
        TaskDef t = new TaskDef();
        t.setId(TASK_ID);
        t.setTenantId(TENANT);
        t.setProjectId(PROJECT);
        t.setDatasourceId(datasourceId);
        t.setTargetDatasourceId(targetDatasourceId);
        t.setType("PYTHON");
        t.setContent("df.read('public.orders')");
        t.setName("etl_task");
        t.setCurrentVersionNo(1);
        return t;
    }

    private static TableRef ref(String qn) {
        return new TableRef(null, qn, null);
    }

    private static IoEdge io(String qn, Source src) {
        return new IoEdge(ref(qn), Direction.READS, src, Confidence.UNVERIFIED);
    }

    private void publish(long taskDefId) {
        LineageAgentEnrichmentRequested ev = new LineageAgentEnrichmentRequested(
                TENANT, PROJECT, taskDefId, "PYTHON", false, List.of(), List.of());
        eventBus.publish(LineageAgentEnrichmentRequested.CHANNEL, objectMapper.writeValueAsString(ev));
    }

    /** 捕获 recordTaskIo 的边集并 countDown。返回捕获槽。 */
    private AtomicReference<List<IoEdge>> captureIoOnRecord(CountDownLatch latch,
                                                            AtomicReference<List<ColumnEdge>> colSlot) {
        AtomicReference<List<IoEdge>> ioSlot = new AtomicReference<>();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<IoEdge> mergedIo = (List<IoEdge>) inv.getArgument(5);
            @SuppressWarnings("unchecked")
            List<ColumnEdge> mergedCol = (List<ColumnEdge>) inv.getArgument(6);
            ioSlot.set(new ArrayList<>(mergedIo));
            if (colSlot != null) colSlot.set(new ArrayList<>(mergedCol));
            latch.countDown();
            return null;
        }).when(lineageStore).recordTaskIo(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any());
        return ioSlot;
    }

    // ── ① 核心：AI-off 下 push→异步接地升级/剔除/连带/审计 ─────────────

    @Test
    void aiOff_push_triggers_grounding_upgrade_drop_cascade_and_audit() throws Exception {
        when(taskDefRepository.findById(TASK_ID)).thenReturn(Optional.of(task(DS_ID, null)));
        when(scriptLineageService.handles("PYTHON")).thenReturn(true);
        // 候选：真表(PRESENT,确定性) / 幻觉临时表(ABSENT,推断类→剔) / 确定性缺表(ABSENT,确定性→留)
        List<IoEdge> ioEdges = List.of(
                io("public.orders", Source.SQL_PARSED),
                io("tmp_stage", Source.SCRIPT_INFERRED),
                io("ghost_tbl", Source.SQL_PARSED));
        List<ColumnEdge> colEdges = List.of(
                new ColumnEdge(ref("tmp_stage"), "a", ref("public.orders"), "x", Transform.DIRECT, Confidence.UNVERIFIED),
                new ColumnEdge(ref("public.orders"), "x", ref("public.orders"), "y", Transform.DIRECT, Confidence.UNVERIFIED));
        when(scriptLineageService.extract(any()))
                .thenReturn(new ScriptLineageService.Result(ioEdges, colEdges, List.of()));

        DatasourceSchemaResolver resolver = probingResolver(java.util.Map.of(
                "public.orders", TableExistence.PRESENT,
                "tmp_stage", TableExistence.ABSENT,
                "ghost_tbl", TableExistence.ABSENT));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<ColumnEdge>> colSlot = new AtomicReference<>();
        AtomicReference<List<IoEdge>> ioSlot = captureIoOnRecord(latch, colSlot);

        newEnricher(realGrounding(), resolver, true);
        publish(TASK_ID);

        assertThat(latch.await(5, TimeUnit.SECONDS)).as("async enrich must invoke recordTaskIo").isTrue();

        List<IoEdge> recorded = ioSlot.get();
        List<String> qns = recorded.stream().map(e -> e.table().qualifiedName()).toList();
        // 幻觉临时表被剔；真表 + 确定性缺表保留
        assertThat(qns).containsExactlyInAnyOrder("public.orders", "ghost_tbl");
        assertThat(qns).doesNotContain("tmp_stage");
        // PRESENT 真表升 CONFIRMED（catalog-verified）
        IoEdge orders = recorded.stream()
                .filter(e -> e.table().qualifiedName().equals("public.orders")).findFirst().orElseThrow();
        assertThat(orders.confidence()).isEqualTo(Confidence.CONFIRMED);
        // 确定性 ABSENT 保留但不升级
        IoEdge ghost = recorded.stream()
                .filter(e -> e.table().qualifiedName().equals("ghost_tbl")).findFirst().orElseThrow();
        assertThat(ghost.confidence()).isEqualTo(Confidence.UNVERIFIED);
        // 列级边连带剔除：源表 tmp_stage 被剔 → 仅 orders→orders 存活
        assertThat(colSlot.get()).hasSize(1);
        assertThat(colSlot.get().get(0).srcTable().qualifiedName()).isEqualTo("public.orders");

        // 处置审计落库：ADOPTED + DROPPED + RETAINED 三条（UNKNOWN 不落，此例无 UNKNOWN）
        ArgumentCaptor<com.dataweave.master.application.lineage.grounding.GroundingDisposition> cap =
                ArgumentCaptor.forClass(com.dataweave.master.application.lineage.grounding.GroundingDisposition.class);
        verify(dispositionRepository, org.mockito.Mockito.atLeast(3))
                .insert(anyLong(), anyLong(), any(), cap.capture());
        List<String> dispositions = cap.getAllValues().stream()
                .map(com.dataweave.master.application.lineage.grounding.GroundingDisposition::disposition).toList();
        assertThat(dispositions).contains("ADOPTED", "DROPPED", "RETAINED");
    }

    // ── ② SC-004 故障注入：grounding 抛异常 → 仍写未接地边集、不断链 ──

    @Test
    void groundingStageThrows_stillRecordsUngroundedEdges_noEscape() throws Exception {
        when(taskDefRepository.findById(TASK_ID)).thenReturn(Optional.of(task(DS_ID, null)));
        when(scriptLineageService.handles("PYTHON")).thenReturn(true);
        List<IoEdge> ioEdges = List.of(
                io("public.orders", Source.SQL_PARSED),
                io("tmp_stage", Source.SCRIPT_INFERRED));
        when(scriptLineageService.extract(any()))
                .thenReturn(new ScriptLineageService.Result(ioEdges, List.of(), List.of()));

        // grounding 服务整段抛异常（模拟探针/仓储级故障冒泡到 stage）
        CatalogGroundingService boom = mock(CatalogGroundingService.class);
        when(boom.ground(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("probe boom"));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<IoEdge>> ioSlot = captureIoOnRecord(latch, null);

        newEnricher(boom, probingResolver(java.util.Map.of()), true);
        publish(TASK_ID);

        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("grounding failure must NOT break recordTaskIo (SC-004)").isTrue();
        // 未接地：两条边原样保留（含推断临时表——因 grounding 未生效，不剔）
        List<String> qns = ioSlot.get().stream().map(e -> e.table().qualifiedName()).toList();
        assertThat(qns).containsExactlyInAnyOrder("public.orders", "tmp_stage");
        // 无处置审计落库（stage 未成功）
        verify(dispositionRepository, never()).insert(anyLong(), anyLong(), any(), any());
    }

    // ── ③ 等价性 I2：kill-switch 关闭 + AI 关闭 → 异步零 recordTaskIo ──

    @Test
    void groundingDisabledAndAiOff_noAsyncRewrite() throws Exception {
        when(taskDefRepository.findById(TASK_ID)).thenReturn(Optional.of(task(DS_ID, null)));

        CountDownLatch latch = new CountDownLatch(1);
        captureIoOnRecord(latch, null);

        newEnricher(realGrounding(), probingResolver(java.util.Map.of()), /*groundingEnabled=*/false);
        publish(TASK_ID);

        // 早退：不应触发任何 recordTaskIo
        assertThat(latch.await(800, TimeUnit.MILLISECONDS))
                .as("grounding-off + AI-off must early-return without recordTaskIo").isFalse();
        verify(lineageStore, never()).recordTaskIo(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any());
    }

    // ── ④ 解绑数据源：无目录 → 异步零 recordTaskIo（与接地前一致）──

    @Test
    void unboundDatasource_noGroundingNoAsyncRewrite() throws Exception {
        when(taskDefRepository.findById(TASK_ID)).thenReturn(Optional.of(task(null, null)));

        CountDownLatch latch = new CountDownLatch(1);
        captureIoOnRecord(latch, null);

        newEnricher(realGrounding(), probingResolver(java.util.Map.of()), true);
        publish(TASK_ID);

        assertThat(latch.await(800, TimeUnit.MILLISECONDS))
                .as("unbound datasource must not trigger grounding rewrite").isFalse();
        verify(lineageStore, never()).recordTaskIo(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any());
    }
}
