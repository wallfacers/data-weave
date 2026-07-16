package com.dataweave.master.companion.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dataweave.master.companion.domain.ChatCallbacks;
import com.dataweave.master.companion.domain.ChatHandle;
import com.dataweave.master.companion.domain.CompanionBrain;
import com.dataweave.master.companion.domain.CompanionEvent;
import com.dataweave.master.companion.domain.CompanionMessage;
import com.dataweave.master.companion.domain.CompanionRoles;
import com.dataweave.master.companion.domain.MessageView;
import com.dataweave.master.companion.domain.PatrolReport;
import com.dataweave.master.companion.infrastructure.JdbcCompanionMessageRepository;
import com.dataweave.master.companion.infrastructure.JdbcPatrolReportRepository;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 管家对话服务（T024）。镜像 070 {@code IncidentConversationService} 的对话流式范式，但大脑是
 * {@link CompanionBrain}（workhorse sidecar）而非直连 LLM。
 *
 * <ul>
 *   <li>{@link #chat}：服务端认定 actor；{@code reportId} 非空时注入该汇报巡检上下文到 brain 会话（FR-013）；
 *       HUMAN 消息立即落库回显，AGENT 回复异步流式（delta/end SSE）。brain 不可用 → {@code companion.brain_unavailable}。</li>
 *   <li>{@link #cancel}：L0 免审批走闸门留痕 + 打断在途流式（1s 内 end{interrupted:true}）。</li>
 * </ul>
 *
 * <p><b>身份透传</b>：{@code TenantContext} 是 ThreadLocal，不过线程池——chat 从请求线程取身份作参数传入，
 * 异步 respond 只用参数，绝不读 TenantContext。
 */
@Service
public class CompanionChatService {

    private static final Logger log = LoggerFactory.getLogger(CompanionChatService.class);

    private final JdbcCompanionMessageRepository messageRepo;
    private final JdbcPatrolReportRepository reportRepo;
    private final CompanionBrainSelector brainSelector;
    private final CompanionEventPublisher publisher;
    private final CompanionTurnRegistry turnRegistry;
    private final CompanionStateResolver stateResolver;
    private final GatedActionService gatedActionService;
    private final ExecutorService chatPool;
    private final ConcurrentHashMap<String, ChatHandle> activeHandles = new ConcurrentHashMap<>();

    public CompanionChatService(JdbcCompanionMessageRepository messageRepo, JdbcPatrolReportRepository reportRepo,
                                CompanionBrainSelector brainSelector, CompanionEventPublisher publisher,
                                CompanionTurnRegistry turnRegistry, CompanionStateResolver stateResolver,
                                GatedActionService gatedActionService,
                                @Value("${companion.chat.threads:2}") int threads) {
        this.messageRepo = messageRepo;
        this.reportRepo = reportRepo;
        this.brainSelector = brainSelector;
        this.publisher = publisher;
        this.turnRegistry = turnRegistry;
        this.stateResolver = stateResolver;
        this.gatedActionService = gatedActionService;
        this.chatPool = Executors.newFixedThreadPool(Math.max(1, threads),
                r -> { Thread t = new Thread(r, "companion-chat"); t.setDaemon(true); return t; });
    }

    /**
     * 发送消息。服务端认定 actor（body 自报忽略）；{@code reportId} 非空时锚定该汇报上下文。
     * 同步落库 + 回显 USER 消息；AGENT 回复异步流式（delta/end）。brain 不可用同步抛 {@code companion.brain_unavailable}。
     */
    public MessageView chat(long tenantId, long projectId, Long reportId, String content,
                            String actor, String actorName, Locale locale) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) throw new BizException("companion.chat_empty");
        if (reportId != null) {
            reportRepo.findById(reportId)
                    .filter(r -> r.tenantId() == tenantId && r.projectId() == projectId)
                    .orElseThrow(() -> new BizException("companion.report_not_found", reportId));
        }
        // brain 不可用：同步降级提示（FR-016，非静默、非空白）
        if (brainSelector.forChat().isEmpty()) throw new BizException("companion.brain_unavailable");
        // MINOR①：同会话已有进行中的流式轮次 → 拒绝（防 handle 覆盖/误删，对齐 070 session-busy 先例）
        if (activeHandles.containsKey(sessionKey(projectId, reportId))) {
            throw new BizException("companion.chat_busy");
        }

        long userMsgId = messageRepo.insert(tenantId, projectId, reportId, CompanionRoles.USER, actor, actorName, text, null);
        CompanionMessage userMsg = findMessage(userMsgId);
        publisher.publish(projectId, new CompanionEvent.MessageAppended(MessageView.from(userMsg)));

        chatPool.submit(() -> {
            try {
                respond(tenantId, projectId, reportId, text, locale);
            } catch (Exception e) {
                log.warn("[CompanionChat] respond 异常 project={}: {}", projectId, e.toString());
            }
        });
        return MessageView.from(userMsg);
    }

    /**
     * 异步流式回复。markThink→(复用/新建 brain session)→send 全程在同一 try/finally（M1：openChat 抛异常时
     * turnRegistry 仍被 clear，不致项目永久卡 think）；失败补系统兜底消息 + StreamEnd（非空白）。
     * M4：优先复用同 sessionKey 的既有 brain session 续聊，复用句柄首条 send 失效(404/过期)则新建重试一次。
     * MINOR①：putIfAbsent 并发轮次互斥——同会话已有进行中轮次则本轮放弃。
     */
    private void respond(long tenantId, long projectId, Long reportId, String userText, Locale locale) {
        CompanionBrain brain = brainSelector.forChat().orElse(null);
        if (brain == null) {
            // 极小竞态窗口：chat 时 healthy，respond 时掉线 → 兜底系统消息
            long sysId = messageRepo.insert(tenantId, projectId, reportId, CompanionRoles.SYSTEM, "system", null,
                    "管家大脑当前不可用，请稍后重试。", null);
            publisher.publish(projectId, new CompanionEvent.MessageAppended(MessageView.from(findMessage(sysId))));
            return;
        }
        String contextPrompt = buildContextPrompt(tenantId, projectId, reportId);
        String turnId = UUID.randomUUID().toString();
        String sessionKey = sessionKey(projectId, reportId);
        StringBuilder full = new StringBuilder();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean brainError = new AtomicBoolean(false);   // 区分 brain 报错与用户打断

        ChatCallbacks cb = new ChatCallbacks() {
            @Override public void onDelta(String chunk) {
                full.append(chunk);
                publisher.publish(projectId, new CompanionEvent.Delta(turnId, chunk));
                turnRegistry.markSpeak(projectId);   // 流式中 → speak 形态
            }
            @Override public void onEnd(String text, boolean irpt) {
                interrupted.set(irpt);
            }
            @Override public void onError(Throwable error) {
                brainError.set(true);
                interrupted.set(true);
                log.warn("[CompanionChat] brain stream error project={}: {}", projectId, error.toString());
            }
        };

        turnRegistry.markThink(projectId);   // 接到指令未回流 → think 形态
        stateResolver.resolveAndNotify(tenantId, projectId);
        ChatHandle handle = null;
        boolean reused = false;
        try {
            // M4：复用同 sessionKey 的既有 brain session 续聊（多轮记忆）；无则新建
            Optional<String> existing = messageRepo.findLatestBrainSession(tenantId, projectId, reportId);
            if (existing.isPresent()) {
                Optional<ChatHandle> resumed = brain.resumeChat(existing.get(), cb);
                if (resumed.isPresent()) { handle = resumed.get(); reused = true; }
            }
            if (handle == null) handle = brain.openChat(projectId, contextPrompt, cb);

            if (activeHandles.putIfAbsent(sessionKey, handle) != null) return;   // MINOR①：并发轮次互斥，本轮放弃
            try {
                handle.send(userText);   // 阻塞至本轮结束；期间经 cb 回调 delta
            } catch (RuntimeException e) {
                if (!reused) throw e;   // 非复用句柄的失败由外层兜底
                // 复用 session 同步抛错(404/过期) → 新建重试一次
                log.debug("[CompanionChat] 复用 session={} 失效，新建重试: {}", existing.orElse("?"), e.toString());
                reused = false;
                activeHandles.remove(sessionKey, handle);
                brainError.set(false); interrupted.set(false); full.setLength(0);
                handle = brain.openChat(projectId, contextPrompt, cb);
                activeHandles.put(sessionKey, handle);
                handle.send(userText);
            }
            // M4b：复用句柄的流经**异步 onError** 失效时 send 并不抛（如 workhorse 重启致旧 session 404，
            // 走 cb.onError 而非同步异常）——此处按「brain 报错且零输出」补一次新建重试。否则旧 session
            // 永久 404、chat 永远落系统兜底错误，且因失败不写 AGENT 消息、DB 里陈旧 brain_session_id 无法自愈。
            if (reused && brainError.get() && full.length() == 0) {
                log.info("[CompanionChat] 复用 session={} 流异步失效，新建重试", existing.orElse("?"));
                activeHandles.remove(sessionKey, handle);
                brainError.set(false); interrupted.set(false); full.setLength(0);
                handle = brain.openChat(projectId, contextPrompt, cb);
                activeHandles.put(sessionKey, handle);
                handle.send(userText);
            }
        } catch (Exception e) {
            brainError.set(true);
            interrupted.set(true);
            log.warn("[CompanionChat] respond brain 异常 project={}: {}", projectId, e.toString());
        } finally {
            // M1：无论 openChat/send 成败都清活跃句柄 + 形态，绝不卡 think
            if (handle != null) activeHandles.remove(sessionKey, handle);
            turnRegistry.clear(projectId);
        }

        String brainSessionId = handle != null ? handle.sessionId() : null;
        if (brainError.get() && full.length() == 0) {
            // M1：brain 报错且无半截输出 → 系统兜底消息（非空白）
            long sysId = messageRepo.insert(tenantId, projectId, reportId, CompanionRoles.SYSTEM, "system", null,
                    "管家回复时出错，请稍后重试。", null);
            publisher.publish(projectId, new CompanionEvent.MessageAppended(MessageView.from(findMessage(sysId))));
        } else {
            long agentMsgId = messageRepo.insert(tenantId, projectId, reportId, CompanionRoles.AGENT,
                    "companion-agent", "Vega", full.toString(), brainSessionId);
            publisher.publish(projectId, new CompanionEvent.MessageAppended(MessageView.from(findMessage(agentMsgId))));
        }
        publisher.publish(projectId, new CompanionEvent.StreamEnd(turnId, interrupted.get()));
        stateResolver.resolveAndNotify(tenantId, projectId);   // think/speak → idle 回落
    }

    /**
     * 打断当前会话的流式输出（L0 免审批走闸门留痕 + handle.cancel，1s 内 end{interrupted:true}）。
     * MINOR②：仅 {@link GateResult#executed()}（L0 实际执行）才发 cancel——防持久卷无 seed 时默认 L2
     * 却照样执行的技术性旁路。
     */
    public boolean cancel(long tenantId, long projectId, Long reportId, String actor, Locale locale) {
        String sessionKey = sessionKey(projectId, reportId);
        ActionRequest req = ActionRequest.builder()
                .toolName("companion_chat_cancel").actionType("COMPANION_CHAT_CANCEL")
                .targetType("COMPANION_SESSION").targetId(sessionKey)
                .actor(actor != null && !actor.isBlank() ? actor : "user").actorSource("UI")
                .summary("打断管家流式输出（会话 " + sessionKey + "）")
                .build();
        GateResult result = gatedActionService.submit(req, locale);   // 留痕 + 裁决
        if (!result.executed()) return false;   // 非 EXECUTED（默认 L2 待审批/被拒）不执行打断
        ChatHandle h = activeHandles.get(sessionKey);
        if (h != null) {
            h.cancel();   // → brain interrupted → send 返回半截 → end{interrupted:true}
            return true;
        }
        return false;
    }

    /** 历史消息（全局 reportId=null 或锚定会话）。 */
    public List<MessageView> history(long tenantId, long projectId, Long reportId, LocalDateTime before, int limit) {
        return messageRepo.findByProject(tenantId, projectId, reportId, before, limit).stream()
                .map(MessageView::from).toList();
    }

    private String buildContextPrompt(long tenantId, long projectId, Long reportId) {
        if (reportId == null) {
            return "用户与管家 Vega 进行全局对话。请基于本项目真实数据回答，泛泛作答等同于失职。";
        }
        PatrolReport r = reportRepo.findById(reportId).orElse(null);
        if (r == null) return "用户与管家 Vega 对话。";
        return "用户正就以下巡检汇报追问，请基于该汇报的上下文回答，而非泛泛作答：\n"
                + "领域：" + r.domain() + "；严重度：" + r.severity() + "\n"
                + "标题：" + r.title() + "\n摘要：" + (r.summary() == null ? "" : r.summary())
                + "\n明细：" + (r.detailJson() == null ? "{}" : r.detailJson());
    }

    private CompanionMessage findMessage(long id) {
        CompanionMessage m = messageRepo.findById(id);
        if (m == null) throw new IllegalStateException("companion_message 未找到: " + id);
        return m;
    }

    private static String sessionKey(long projectId, Long reportId) {
        return projectId + ":" + (reportId == null ? "global" : reportId);
    }
}
