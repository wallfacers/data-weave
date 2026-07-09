package com.dataweave.master.application;

import com.dataweave.master.application.readiness.ReadinessInitializer;
import com.dataweave.master.domain.Envs;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.InstanceStates;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkflowDagSnapshot;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefVersion;
import com.dataweave.master.domain.WorkflowDefVersionRepository;
import com.dataweave.master.domain.WorkflowInstance;
import com.dataweave.master.domain.WorkflowInstanceRepository;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowEdgeRepository;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.domain.WorkflowNodeFreeze;
import com.dataweave.master.domain.WorkflowNodeFreezeRepository;
import com.dataweave.master.domain.WorkflowNodeRepository;
import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工作流/任务触发与实例创建（task 2.7/2.10/2.11 共用）。
 *
 * <p>创建实例时所有节点统一置 WAITING——「等待不占资源」：上游未就绪的节点由调度内核的可运行门
 * （上游 SUCCESS 才认领）自然挡住，不必显式 NOT_RUN→WAITING 解锁过渡。创建后发布唤醒触发即时调度。
 *
 * <p>每个实例落 {@code locale}（触发者 BCP-47 tag）——供任务运行日志 banner 按触发者 locale 渲染
 * （i18n 规则②）。cron 触发由调用方传 {@link com.dataweave.master.i18n.Messages#DEFAULT_LOCALE}。
 */
@Service
public class WorkflowTriggerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerService.class);

    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final TaskInstanceRepository taskInstanceRepository;
    private final TaskDefRepository taskDefRepository;
    private final WorkflowDefVersionRepository workflowDefVersionRepository;
    private final WorkflowNodeFreezeRepository nodeFreezeRepository;
    private final WorkflowStateService workflowStateService;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final ReadinessInitializer readinessInitializer;
    private final JdbcTemplate jdbc;

    public WorkflowTriggerService(WorkflowNodeRepository nodeRepository,
                                  WorkflowEdgeRepository edgeRepository,
                                  WorkflowInstanceRepository workflowInstanceRepository,
                                  TaskInstanceRepository taskInstanceRepository,
                                  TaskDefRepository taskDefRepository,
                                  WorkflowDefVersionRepository workflowDefVersionRepository,
                                  WorkflowNodeFreezeRepository nodeFreezeRepository,
                                  WorkflowStateService workflowStateService,
                                  EventBus eventBus,
                                  ObjectMapper objectMapper,
                                  ReadinessInitializer readinessInitializer,
                                  JdbcTemplate jdbc) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.taskInstanceRepository = taskInstanceRepository;
        this.taskDefRepository = taskDefRepository;
        this.workflowDefVersionRepository = workflowDefVersionRepository;
        this.nodeFreezeRepository = nodeFreezeRepository;
        this.workflowStateService = workflowStateService;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.readinessInitializer = readinessInitializer;
        this.jdbc = jdbc;
    }

    /**
     * 触发一个工作流：建 workflow_instance + 各节点 task_instance（全 WAITING），发布唤醒。
     *
     * @param locale 触发者 locale（agent 触发传 agent locale；cron 触发传默认中文）
     * @return 新建 workflow_instance 的 id；工作流无节点抛 {@link IllegalStateException}
     */
    public UUID trigger(WorkflowDef wf, String triggerType, String bizDate, Integer priorityOverride,
                        Locale locale) {
        return trigger(wf, triggerType, bizDate, priorityOverride, locale, "FULL", null);
    }

    /**
     * 触发一个工作流：建 workflow_instance + 各节点 task_instance（全 WAITING），发布唤醒。
     *
     * @param scope         运行范围：FULL=全部节点；TO_NODE=target 及其前驱闭包；DOWNSTREAM=target 及其后继闭包
     *                      （design D5）。子图物化与就绪门天然契合：子图外的上游无 pred 实例，就绪门 JOIN 落空
     *                      即不阻塞——故 DOWNSTREAM 的 target 直跑、TO_NODE 内部按同周期级联。
     * @param targetNodeKey scope 非 FULL 时的目标节点 node_key
     * @param locale        触发者 locale（agent 触发传 agent locale；cron 触发传默认中文）
     * @return 新建 workflow_instance 的 id；工作流无节点/范围为空抛 {@link IllegalStateException}
     */
    public UUID trigger(WorkflowDef wf, String triggerType, String bizDate, Integer priorityOverride,
                        Locale locale, String scope, String targetNodeKey) {
        return trigger(wf, triggerType, bizDate, priorityOverride, locale, scope, targetNodeKey,
                "NORMAL", null, 0, null);
    }

    /**
     * 触发一个工作流，并指定运行模式与补数据批次（data-ops-center）。{@code runMode=BACKFILL} 时各节点实例落
     * BACKFILL 标识 + {@code backfillRunId}，认领/执行复用既有路径（仅 runMode 标识，无专用认领分支）。
     * 其余语义同 {@link #trigger(WorkflowDef, String, String, Integer, Locale, String, String)}。
     *
     * @param backfillHeld 补数据节流持有标志（backfill-parallelism-throttle）：1=该 bizDate 整张 DAG 节点落
     *                     {@code backfill_held=1}（INSERT 时即定，根除「插入后→置 held 前」被抢认领的竞态），
     *                     由 BackfillPromoter 完成即晋升 1→0；非补数据路径恒传 0。
     */
    @Transactional
    public UUID trigger(WorkflowDef wf, String triggerType, String bizDate, Integer priorityOverride,
                        Locale locale, String scope, String targetNodeKey,
                        String runMode, UUID backfillRunId, int backfillHeld,
                        LocalDateTime scheduledFireTime) {
        bizDate = defaultBizDate(bizDate);

        // nodeKey -> live workflow_node.id：物化 task_instance.workflow_node_id 外键用（事件流/节点变色按此 id）。
        // 拓扑与版本仍以快照为准——live node 仅供取稳定 nodeKey 对应的物理主键。
        List<WorkflowNode> liveNodes = nodeRepository.findByWorkflowIdAndDeleted(wf.getId(), 0);
        if (liveNodes.isEmpty()) {
            throw new BizException("workflow.trigger.no_nodes", wf.getId());
        }
        Map<String, Long> keyToLiveId = new HashMap<>();
        Map<Long, String> idToKey = new HashMap<>();
        for (WorkflowNode n : liveNodes) {
            keyToLiveId.put(n.getNodeKey(), n.getId());
            idToKey.put(n.getId(), n.getNodeKey());
        }

        // 物化真相源（workflow-version-binding D1）：优先已发布快照（规定性——拓扑+版本钉死），
        // 无快照（从未发布工作流）回退 live 物化（兼容现状）。
        List<MatNode> matNodes = new ArrayList<>();
        Map<String, List<String>> forward = new HashMap<>();   // fromKey -> [toKey]（后继）
        Map<String, List<String>> backward = new HashMap<>();  // toKey -> [fromKey]（前驱）
        Optional<WorkflowDagSnapshot> snapOpt = loadSnapshot(wf.getId(), wf.getCurrentVersionNo());
        if (snapOpt.isPresent()) {
            WorkflowDagSnapshot snap = snapOpt.get();
            for (WorkflowDagSnapshot.Node n : snap.nodes()) {
                Long liveId = keyToLiveId.get(n.nodeKey());
                if (liveId == null) {
                    // 快照节点在 live 已删（发布后又删节点未重新晋级）：跳过物化 + 告警，不静默丢；
                    // 该工作流读侧会被判 DAG 草稿漂移，提示用户重新晋级（workflow-version-binding）。
                    log.warn("触发跳过快照节点：nodeKey={} 在 live workflow_node 已不存在（workflowId={}，需重新晋级）",
                            n.nodeKey(), wf.getId());
                    continue;
                }
                matNodes.add(new MatNode(n.nodeKey(),
                        n.nodeType() != null ? n.nodeType() : "TASK",
                        n.taskId(), n.taskVersionNo(), liveId));
            }
            for (WorkflowDagSnapshot.Edge e : snap.edges()) {
                addEdge(forward, backward, e.fromNodeKey(), e.toNodeKey());
            }
        } else {
            // 回退 live：拓扑与版本读 live（current_version_no）——仅未发布工作流走此路。
            for (WorkflowNode n : liveNodes) {
                Integer versionNo = n.getTaskId() != null
                        ? taskDefRepository.findById(n.getTaskId())
                                .map(TaskDef::getCurrentVersionNo).orElse(null)
                        : null;
                matNodes.add(new MatNode(n.getNodeKey(),
                        n.getNodeType() != null ? n.getNodeType() : "TASK",
                        n.getTaskId(), versionNo, n.getId()));
            }
            for (WorkflowEdge e : edgeRepository.findByWorkflowIdAndDeleted(wf.getId(), 0)) {
                String from = idToKey.get(e.getFromNodeId());
                String to = idToKey.get(e.getToNodeId());
                if (from != null && to != null) {
                    addEdge(forward, backward, from, to);
                }
            }
        }
        if (matNodes.isEmpty()) {
            throw new BizException("workflow.trigger.no_materialized", wf.getId());
        }

        // 运行范围子图（design D5）：按 nodeKey 闭包。子图外节点不物化。
        Set<String> allKeys = matNodes.stream().map(MatNode::nodeKey).collect(Collectors.toSet());
        Set<String> subKeys = computeSubgraphKeys(allKeys, forward, backward, scope, targetNodeKey);
        List<MatNode> subNodes = matNodes.stream()
                .filter(m -> subKeys.contains(m.nodeKey())).toList();
        if (subNodes.isEmpty()) {
            throw new BizException("workflow.trigger.empty_scope", scope, targetNodeKey);
        }
        LocalDateTime now = LocalDateTime.now();

        // 定义级节点冻结（ops-center-publish-boundary）：叠加 overlay，冻结节点及其传递下游（含弱依赖边）→ SKIPPED。
        // 实例级冻结在 NodeFreezeService 于实例已存在后追加，不在物化期处理。
        Set<String> frozenSeeds = nodeFreezeRepository.findDefinitionFrozen(wf.getId())
                .stream().map(WorkflowNodeFreeze::getNodeKey).collect(Collectors.toSet());
        Set<String> frozenClosure = frozenSeeds.isEmpty()
                ? Set.of() : downstreamClosure(frozenSeeds, forward);

        // 虚拟节点（zero-load）物化即 SUCCESS，计入已完成数（被冻结的虚拟节点改记 SKIPPED，不计此）。
        int virtualCount = (int) subNodes.stream()
                .filter(m -> "VIRTUAL".equals(m.nodeType()) && !frozenClosure.contains(m.nodeKey())).count();

        WorkflowInstance wi = new WorkflowInstance();
        wi.setTenantId(wf.getTenantId());
        wi.setProjectId(wf.getProjectId());
        wi.setWorkflowId(wf.getId());
        wi.setWorkflowVersionNo(wf.getCurrentVersionNo());  // 名副其实：物化的就是此快照版本
        wi.setEnv(Envs.PROD);                               // cron/正式手动落 PROD
        wi.setTriggerType(triggerType);
        wi.setState(InstanceStates.RUNNING);
        wi.setPriority(priorityOverride != null ? priorityOverride
                : (wf.getPriority() != null ? wf.getPriority() : 5));
        wi.setWorkflowDefName(wf.getName());                // 快照：工作流名称
        wi.setCronExpression(wf.getCron());                 // 快照：调度 cron
        wi.setScheduledFireTime(scheduledFireTime);         // 快照：cron/fixed_rate 计划触发时刻（手动/补数据为 null）
        wi.setBizDate(bizDate);
        wi.setTotalTasks(subNodes.size());
        wi.setCompletedTasks(virtualCount);
        wi.setFailedTasks(0);
        wi.setStartedAt(now);
        wi.setCreatedAt(now);
        wi.setUpdatedAt(now);
        wi.setDeleted(0);
        wi.setVersion(0L);
        WorkflowInstance savedWi = workflowInstanceRepository.save(wi);

        // 批量预取 task 名称+类型，避免循环内逐条查 taskDef
        java.util.Map<Long, String> taskIdToName = new java.util.HashMap<>();
        java.util.Map<Long, String> taskIdToType = new java.util.HashMap<>();
        for (MatNode m : subNodes) {
            if (m.taskId() != null && !taskIdToName.containsKey(m.taskId())) {
                taskDefRepository.findById(m.taskId())
                        .ifPresent(td -> {
                            taskIdToName.put(m.taskId(), td.getName());
                            taskIdToType.put(m.taskId(), td.getType());
                        });
            }
        }

        boolean anyPending = false;  // 是否存在会真正进调度队列的 WAITING 节点
        List<TaskInstance> taskInstances = new ArrayList<>(subNodes.size());  // 045 批量物化(替代循环逐条 save)
        for (MatNode m : subNodes) {
            TaskInstance ti = newTaskInstance(wf.getTenantId(), wf.getProjectId(), now, locale);
            ti.setWorkflowInstanceId(savedWi.getId());
            ti.setWorkflowNodeId(m.liveNodeId());
            ti.setRunMode(runMode);
            ti.setBackfillRunId(backfillRunId);
            ti.setBackfillHeld(backfillHeld);   // 整张 DAG 同 held（晋升以 bizDate 为单位，design D2）
            ti.setEnv(Envs.PROD);
            ti.setBizDate(bizDate);
            ti.setWorkflowDefName(wf.getName());                // 快照：工作流名称
            ti.setCronExpression(wf.getCron());                 // 快照：调度 cron
            ti.setTaskDefName(m.taskId() != null ? taskIdToName.get(m.taskId()) : null);  // 快照：任务名称
            ti.setTaskType(m.taskId() != null ? taskIdToType.get(m.taskId()) : null);    // 快照：任务类型
            boolean frozen = frozenClosure.contains(m.nodeKey());
            if ("VIRTUAL".equals(m.nodeType())) {
                // VIRTUAL：零负载锚点，物化即成功——不绑 task、不下发、不占槽；被冻结则记 SKIPPED。
                ti.setTaskId(null);
                ti.setTaskVersionNo(null);
                ti.setState(frozen ? InstanceStates.SKIPPED : InstanceStates.SUCCESS);
                ti.setStartedAt(now);
                ti.setFinishedAt(now);
            } else {
                Integer versionNo = m.taskVersionNo();
                ti.setTaskId(m.taskId());
                ti.setTaskVersionNo(versionNo != null && versionNo > 0 ? versionNo : null);
                if (frozen) {
                    // 冻结节点（及其传递下游）：物化即 SKIPPED，不下发不占槽。
                    ti.setState(InstanceStates.SKIPPED);
                    ti.setStartedAt(now);
                    ti.setFinishedAt(now);
                } else {
                    ti.setState(InstanceStates.WAITING);
                    anyPending = true;
                }
            }
            taskInstances.add(ti);  // 045 收集(循环外批量 saveAll)
        }
        taskInstanceRepository.saveAll(taskInstances);  // 045 批量 INSERT(N 次往返→1 次,减 commit)

        // 051: 对 WAITING 实例初始化 unmet_deps 初值（权威重算，只计未满足依赖）
        List<UUID> waitingIds = new ArrayList<>();
        for (TaskInstance ti : taskInstances) {
            if (InstanceStates.WAITING.equals(ti.getState()) && ti.getId() != null) {
                waitingIds.add(ti.getId());
            }
        }
        if (!waitingIds.isEmpty()) {
            try {
                java.util.Map<UUID, Integer> unmetMap = readinessInitializer.initialize(waitingIds);
                for (var entry : unmetMap.entrySet()) {
                    jdbc.update("UPDATE task_instance SET unmet_deps = ? WHERE id = ?",
                            entry.getValue(), entry.getKey());
                }
            } catch (Exception e) {
                // fail-closed：整体失败时把本批 WAITING 全部置未就绪哨兵，绝不留默认 0 被提前认领；Reconciler 权威纠正。
                log.warn("[Trigger] 就绪态初值计算整体失败，fail-closed 置未就绪待对账：{}", e.getMessage());
                for (UUID id : waitingIds) {
                    jdbc.update("UPDATE task_instance SET unmet_deps = ? WHERE id = ?", Integer.MAX_VALUE, id);
                }
            }
        }

        // 物化落库的 RUNNING 是乐观初值（设计意图：触发即视为运行中，不给用户看"等待中"的中间态；
        // 正常场景很快会被调度认领的节点事件自然推进/校正）。仅当整批节点里**没有任何 WAITING 节点**
        // （全虚拟锚点 / 全冻结跳过）时才补一次聚合校正——这种情况不会再有后续调度事件，
        // 若不在此兜底，实例会永久悬挂 RUNNING（aggregate 应得 SUCCESS）。
        if (!anyPending) {
            workflowStateService.computeAndUpdate(savedWi.getId());
        }

        wakeAfterCommit();  // 045 推迟到事务提交后(dispatch 事务外,守不变量④)
        return savedWi.getId();
    }

    /** 物化节点：统一快照/live 两条物化源的中间表示。{@code liveNodeId} 是 task_instance 外键所需的物理主键。 */
    private record MatNode(String nodeKey, String nodeType, Long taskId, Integer taskVersionNo, Long liveNodeId) {}

    private static void addEdge(Map<String, List<String>> forward, Map<String, List<String>> backward,
                                String from, String to) {
        forward.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        backward.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
    }

    /**
     * 读已发布快照（workflow-version-binding D1）：按 {@code (workflowId, versionNo)} 取 workflow_def_version，
     * 反序列化 dag_snapshot_json。版本号无效（未发布）或解析失败时返回空 → 调用方回退 live 物化。
     */
    private Optional<WorkflowDagSnapshot> loadSnapshot(Long workflowId, Integer versionNo) {
        if (versionNo == null || versionNo <= 0) {
            return Optional.empty();
        }
        return workflowDefVersionRepository.findByWorkflowIdAndVersionNo(workflowId, versionNo)
                .map(WorkflowDefVersion::getDagSnapshotJson)
                .filter(s -> s != null && !s.isBlank())
                .flatMap(json -> {
                    try {
                        return Optional.of(objectMapper.readValue(json, WorkflowDagSnapshot.class));
                    } catch (Exception e) {
                        log.warn("解析 dag_snapshot_json 失败，回退 live 物化（workflowId={}，versionNo={}）：{}",
                                workflowId, versionNo, e.getMessage());
                        return Optional.empty();
                    }
                });
    }

    /**
     * 计算运行范围节点子集（design D5），按 nodeKey 闭包：FULL=全部；TO_NODE=target 及其前驱闭包
     * （跑通上游到本节点）；DOWNSTREAM=target 及其后继闭包（从本节点跑到末端）。沿快照边 BFS；
     * DAG 发布已保证无环，visited 集合兜底防非法图死循环。target 无效或 scope 未识别时降级 FULL。
     */
    private Set<String> computeSubgraphKeys(Set<String> allKeys, Map<String, List<String>> forward,
                                            Map<String, List<String>> backward,
                                            String scope, String targetNodeKey) {
        if (targetNodeKey == null || targetNodeKey.isBlank() || "FULL".equals(scope)
                || !allKeys.contains(targetNodeKey)) {
            return allKeys;  // 全量 / target 无效：降级
        }
        Set<String> result = new HashSet<>();
        result.add(targetNodeKey);
        Map<String, List<String>> graph = "TO_NODE".equals(scope) ? backward : forward;
        Deque<String> queue = new ArrayDeque<>(graph.getOrDefault(targetNodeKey, List.of()));
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (result.add(cur)) {
                queue.addAll(graph.getOrDefault(cur, List.of()));
            }
        }
        return result;
    }

    /**
     * 冻结级联闭包（ops-center-publish-boundary）：从冻结种子节点沿后继边 BFS 取传递下游闭包，
     * **含弱依赖边**（冻结优先于依赖强弱，下游一律跳过）。{@code forward} 为全量 fromKey→[toKey]。
     * 返回含种子自身的全部应 SKIPPED 节点 key 集合。
     */
    static Set<String> downstreamClosure(Set<String> seeds, Map<String, List<String>> forward) {
        Set<String> result = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (result.add(cur)) {
                queue.addAll(forward.getOrDefault(cur, List.of()));
            }
        }
        return result;
    }

    /**
     * 单任务测试运行（design D9）：脱离工作流、跑草稿内容（task_version_no=null）、run_mode=TEST，
     * 不入正式统计。返回 task_instance id。
     */
    public UUID triggerTestRun(Long taskId, String bizDate, Locale locale) {
        return triggerTestRun(taskId, bizDate, null, null, null, locale);
    }

    /**
     * 单任务测试运行（task-run-decouple）：可携带编辑器临时内容（含未保存改动）。
     *
     * <p>{@code contentOverride} 非空时写入 {@code task_instance.content_override}，调度认领时优先于
     * task_def 草稿被取用（见 {@code SchedulerKernel.contentOf}），**不写 task_def**——满足「不管存没存，
     * 跑编辑器最新内容」。为空则回退当前 DB 草稿（如从实例列表 rerun 历史 TEST）。
     *
     * @param contentOverride 编辑器临时脚本内容（可空）
     * @param paramsOverride  编辑器临时调度参数 JSON（可空，与 content 同源用于占位符解析）
     * @param typeOverride    编辑器临时任务类型（可空，非空则覆盖 task_def.type 选执行器）
     * @param locale          触发者 locale（banner 按此渲染）
     */
    public UUID triggerTestRun(Long taskId, String bizDate, String contentOverride,
                               String paramsOverride, String typeOverride, Locale locale) {
        TaskDef task = taskDefRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在：" + taskId));
        bizDate = defaultBizDate(bizDate);
        LocalDateTime now = LocalDateTime.now();
        TaskInstance ti = newTaskInstance(task.getTenantId(), task.getProjectId(), now, locale);
        ti.setWorkflowInstanceId(null);
        ti.setWorkflowNodeId(null);
        ti.setTaskId(taskId);
        ti.setTaskVersionNo(null);          // 草稿
        ti.setContentOverride(contentOverride != null && !contentOverride.isBlank() ? contentOverride : null);
        ti.setParamsOverride(paramsOverride != null && !paramsOverride.isBlank() ? paramsOverride : null);
        ti.setTypeOverride(typeOverride != null && !typeOverride.isBlank() ? typeOverride : null);
        ti.setRunMode("TEST");
        ti.setEnv(Envs.DEV);                // 试跑落 DEV（开发态，跑草稿）
        ti.setState(InstanceStates.WAITING);
        ti.setBizDate(bizDate);
        ti.setTaskDefName(task.getName());              // 快照：任务名称
        ti.setTaskType(task.getType());                  // 快照：任务类型（独立运行，无工作流上下文）
        TaskInstance saved = taskInstanceRepository.save(ti);
        wake();
        return saved.getId();
    }

    /**
     * 单任务正式手动运行（manual-run-trigger）：脱离工作流、跑**已发布版本**
     * （task_version_no=current_version_no）、run_mode=NORMAL，计入正式运维统计。返回 task_instance id。
     *
     * <p>与 {@link #triggerTestRun} 互为镜像——TEST 跑草稿（version=null）不计统计；
     * 本方法跑已发布版本、计统计，是「正式实例」语义。草稿/未发布的拦截在闸门之前（controller），
     * 此处兜底：current_version_no 缺失则按未发布处理（version 落 null）。
     */
    public UUID triggerManualTaskRun(Long taskId, String bizDate, Locale locale) {
        TaskDef task = taskDefRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在：" + taskId));
        bizDate = defaultBizDate(bizDate);
        Integer versionNo = task.getCurrentVersionNo();
        LocalDateTime now = LocalDateTime.now();
        TaskInstance ti = newTaskInstance(task.getTenantId(), task.getProjectId(), now, locale);
        ti.setWorkflowInstanceId(null);
        ti.setWorkflowNodeId(null);
        ti.setTaskId(taskId);
        ti.setTaskVersionNo(versionNo != null && versionNo > 0 ? versionNo : null);  // 跑已发布版本
        ti.setRunMode("NORMAL");
        ti.setEnv(Envs.PROD);               // 正式手动运行落 PROD
        ti.setState(InstanceStates.WAITING);
        ti.setBizDate(bizDate);
        ti.setTaskDefName(task.getName());              // 快照：任务名称
        ti.setTaskType(task.getType());                  // 快照：任务类型（独立运行，无工作流上下文）
        TaskInstance saved = taskInstanceRepository.save(ti);
        wake();
        return saved.getId();
    }

    /**
     * 单任务补数据运行（data-ops-center）：跑**已发布版本**、{@code run_mode=BACKFILL} + {@code backfillRunId}，
     * {@code bizDate} 为补数据指定日期（必传，不走 T-1 兜底——补数据的本意就是指定历史日期）。
     * 认领/执行复用既有路径，仅 runMode 标识。返回 task_instance id。
     */
    public UUID triggerBackfillTaskRun(Long taskId, String bizDate, UUID backfillRunId, int backfillHeld, Locale locale) {
        TaskDef task = taskDefRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在：" + taskId));
        bizDate = defaultBizDate(bizDate);
        Integer versionNo = task.getCurrentVersionNo();
        LocalDateTime now = LocalDateTime.now();
        TaskInstance ti = newTaskInstance(task.getTenantId(), task.getProjectId(), now, locale);
        ti.setWorkflowInstanceId(null);
        ti.setWorkflowNodeId(null);
        ti.setTaskId(taskId);
        ti.setTaskVersionNo(versionNo != null && versionNo > 0 ? versionNo : null);
        ti.setRunMode("BACKFILL");
        ti.setBackfillRunId(backfillRunId);
        ti.setBackfillHeld(backfillHeld);   // 超出 parallelism 配额的 bizDate 持有（backfill-parallelism-throttle）
        ti.setEnv(Envs.PROD);
        ti.setState(InstanceStates.WAITING);
        ti.setBizDate(bizDate);
        ti.setTaskDefName(task.getName());              // 快照：任务名称
        ti.setTaskType(task.getType());                  // 快照：任务类型（独立运行，无工作流上下文）
        TaskInstance saved = taskInstanceRepository.save(ti);
        wake();
        return saved.getId();
    }

    /**
     * 业务日期兜底（contract：{@code $bizdate/$bizmonth} 任何运行模式均有值）：caller 未传则取 T-1
     * （{@code yyyy-MM-dd}），与 cron 触发的 {@code due.minusDays(1)} 及前端编辑器 {@code yesterday()} 同约定。
     * 否则全 SHELL/SQL 节点的 {@code $bizdate} 在调度解析时即抛 {@code schedule.bizdate.empty} 致节点 FAILED、无日志。
     */
    private static String defaultBizDate(String bizDate) {
        return (bizDate == null || bizDate.isBlank())
                ? LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                : bizDate;
    }

    private TaskInstance newTaskInstance(Long tenantId, Long projectId, LocalDateTime now, Locale locale) {
        TaskInstance ti = new TaskInstance();
        ti.setTenantId(tenantId);
        ti.setProjectId(projectId);
        ti.setAttempt(0);
        ti.setLocale(locale != null ? locale.toLanguageTag() : null);
        ti.setCreatedAt(now);
        ti.setUpdatedAt(now);
        ti.setDeleted(0);
        ti.setVersion(0L);
        ti.setBackfillHeld(0);   // 默认不持有；补数据节流路径按配额覆写（backfill-parallelism-throttle）
        return ti;
    }

    private void wake() {
        eventBus.publish(InstanceStates.WAKE_CHANNEL, "trigger");
    }

    /**
     * 045：wake 推迟到当前事务提交后（守死锁不变量④：状态事务内持久化、dispatch/事件发布事务外）。
     * 主 trigger 已 @Transactional，事务提交前 publish 会让调度认领看到未提交实例（READ COMMITTED 下不可见，
     * 导致认领空轮）；afterCommit 回调保证 publish 时实例已落库。无事务上下文（非 trigger 主方法调用）直接 publish。
     */
    private void wakeAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wake();
                }
            });
        } else {
            wake();
        }
    }
}
