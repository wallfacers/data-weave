package com.dataweave.master.companion.application;

import java.util.List;

import com.dataweave.master.companion.domain.CompanionEvent;
import com.dataweave.master.companion.domain.PatrolReport;
import com.dataweave.master.companion.domain.ReportStatuses;
import com.dataweave.master.companion.domain.ReportView;
import com.dataweave.master.companion.infrastructure.JdbcPatrolReportRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Service;

/**
 * 汇报服务（T020）：项目级共享关闭（幂等）、标记已读、列表。
 *
 * <p>关闭为项目级共享（clarify 决议）：任一成员关闭后对项目内全员消失，经 SSE {@code report:closed} 事件实时同步移除。
 * 关闭后异常数变化 → 刷新管家 alert 形态与概况。
 */
@Service
public class ReportService {

    private final JdbcPatrolReportRepository reportRepo;
    private final CompanionEventPublisher publisher;
    private final CompanionStateResolver stateResolver;
    private final CompanionBriefingService briefingService;

    public ReportService(JdbcPatrolReportRepository reportRepo, CompanionEventPublisher publisher,
                         CompanionStateResolver stateResolver, CompanionBriefingService briefingService) {
        this.reportRepo = reportRepo;
        this.publisher = publisher;
        this.stateResolver = stateResolver;
        this.briefingService = briefingService;
    }

    /** 项目级关闭：幂等（已关闭视为成功）；关闭后发 report:closed 事件 + 刷新形态/概况。 */
    public ReportView close(long tenantId, long projectId, long reportId, String closedBy) {
        PatrolReport r = require(tenantId, projectId, reportId);
        if (ReportStatuses.isClosed(r.status())) {
            return ReportView.from(r);   // 幂等：已关闭直接返回成功
        }
        reportRepo.close(reportId, tenantId, projectId, closedBy);
        PatrolReport closed = reportRepo.findById(reportId).orElseThrow();
        publisher.publish(projectId, new CompanionEvent.ReportEvent("closed", ReportView.from(closed)));
        stateResolver.resolveAndNotify(tenantId, projectId);
        briefingService.computeAndNotify(tenantId, projectId);
        return ReportView.from(closed);
    }

    /** 标记已读（未读计数）。 */
    public ReportView read(long tenantId, long projectId, long reportId) {
        PatrolReport r = require(tenantId, projectId, reportId);
        reportRepo.markRead(reportId, tenantId, projectId);
        return ReportView.from(reportRepo.findById(reportId).orElseThrow());
    }

    /** 汇报列表（补看/分页，可选 status 过滤）。 */
    public List<ReportView> list(long tenantId, long projectId, String status, int limit) {
        return reportRepo.findByProject(tenantId, projectId, status, limit).stream()
                .map(ReportView::from).toList();
    }

    private PatrolReport require(long tenantId, long projectId, long reportId) {
        return reportRepo.findById(reportId)
                .filter(r -> r.tenantId() == tenantId && r.projectId() == projectId)
                .orElseThrow(() -> new BizException("companion.report_not_found", reportId));
    }
}
