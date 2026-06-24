package com.dataweave.master.application;

import com.dataweave.master.domain.Finding;
import com.dataweave.master.domain.FindingRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 通用发现的应用服务：去重落库、状态流转、举手台列表。
 *
 * <p>{@code Inspector} 把产出的 {@link Finding} 交给 {@link #recordIfNew}（按
 * (source,targetType,targetId) 对仍活跃的发现去重，不重复举手）；闸门修复成功后经
 * {@link #resolve} 收口；主动播报经 {@link #markAnnounced} 去重。下游只认 Finding，不区分来源。
 */
@Service
public class FindingService {

    /** 仍需处理（出现在举手台/可被去重命中）的状态集合。 */
    static final List<String> ACTIVE = List.of("OPEN", "ANNOUNCED");

    private final FindingRepository repository;

    public FindingService(FindingRepository repository) {
        this.repository = repository;
    }

    /**
     * 去重落库：若已存在同 (source,targetType,targetId) 且仍活跃（OPEN/ANNOUNCED）的发现，直接返回它，
     * 不重复创建；否则补默认值后保存为 OPEN。
     */
    public Finding recordIfNew(Finding finding) {
        Optional<Finding> dup = repository.findFirstBySourceAndTargetTypeAndTargetIdAndStatusInOrderByIdDesc(
                finding.getSource(), finding.getTargetType(), finding.getTargetId(), ACTIVE);
        if (dup.isPresent()) {
            return dup.get();
        }
        LocalDateTime now = LocalDateTime.now();
        if (finding.getTenantId() == null) {
            finding.setTenantId(1L);
        }
        if (finding.getProjectId() == null) {
            finding.setProjectId(1L);
        }
        if (finding.getStatus() == null) {
            finding.setStatus("OPEN");
        }
        if (finding.getSeverity() == null) {
            finding.setSeverity("WARN");
        }
        if (finding.getAnnounced() == null) {
            finding.setAnnounced(0);
        }
        finding.setCreatedAt(now);
        finding.setUpdatedAt(now);
        finding.setDeleted(0);
        finding.setVersion(0);
        return repository.save(finding);
    }

    /** 举手台列表：OPEN/ANNOUNCED，id 降序。 */
    public List<Finding> active() {
        return repository.findByStatusInOrderByIdDesc(ACTIVE);
    }

    /** 该目标是否已有任意发现（含已 RESOLVED）——巡检器据此跳过已处理目标，避免修复后重复举手。 */
    public boolean exists(String source, String targetType, String targetId) {
        return repository.existsBySourceAndTargetTypeAndTargetId(source, targetType, targetId);
    }

    public Optional<Finding> get(Long id) {
        return repository.findById(id);
    }

    /** 修复成功后收口：置 RESOLVED，不再出现在举手台。 */
    public Optional<Finding> resolve(Long id) {
        return repository.findById(id).map(f -> {
            f.setStatus("RESOLVED");
            f.setUpdatedAt(LocalDateTime.now());
            return repository.save(f);
        });
    }

    /** 主动播报去重：标记 announced，并把 OPEN 推进到 ANNOUNCED（已 RESOLVED 不动）。 */
    public Optional<Finding> markAnnounced(Long id) {
        return repository.findById(id).map(f -> {
            f.setAnnounced(1);
            if ("OPEN".equals(f.getStatus())) {
                f.setStatus("ANNOUNCED");
            }
            f.setUpdatedAt(LocalDateTime.now());
            return repository.save(f);
        });
    }
}
