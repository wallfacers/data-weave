package com.dataweave.master.application.asset;

import com.dataweave.master.domain.asset.DataAsset;
import com.dataweave.master.domain.asset.DataAssetRepository;
import com.dataweave.master.domain.asset.Sensitivity;
import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 资产编目 CRUD + 装配（FR-001 / FR-010）。以 (tenant, project, datasource, qualifiedName) 唯一去重。
 *
 * <p>schema 漂移（对账不一致）→ STALE + publish ASSET_CHANGED(schema)（消费方接缝 → 021）。
 * PII 资产仅 owner/steward 可见详情（SC-006）。血缘/质量徽章经独立 assembler 懒加载（降级安全）。
 */
@Service
public class AssetCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AssetCatalogService.class);

    private final DataAssetRepository repository;
    private final AssetSubscriptionService subscriptionService;
    private final JdbcTemplate jdbc;
    private final CatalogMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssetCatalogService(DataAssetRepository repository,
                               AssetSubscriptionService subscriptionService,
                               JdbcTemplate jdbc,
                               CatalogMetrics metrics) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    // ─── 写 ────────────────────────────────────────────────────

    /** 编目资产（去重 → catalog.duplicate_asset）。payload 字段见 data-model。 */
    @Transactional
    public DataAsset create(long tenantId, long projectId, long userId, Map<String, Object> payload) {
        long datasourceId = longOr(payload.get("datasourceId"), 0L);
        String qualifiedName = str(payload.get("qualifiedName"));
        if (qualifiedName == null || qualifiedName.isBlank()) {
            throw new BizException("catalog.asset_invalid").withHttpStatus(400);
        }
        repository.findFirstByTenantIdAndProjectIdAndDatasourceIdAndQualifiedNameAndDeleted(
                        tenantId, projectId, datasourceId, qualifiedName, 0)
                .ifPresent(a -> { throw new BizException("catalog.duplicate_asset", qualifiedName).withHttpStatus(409); });

        LocalDateTime now = LocalDateTime.now();
        DataAsset a = new DataAsset();
        a.setTenantId(tenantId);
        a.setProjectId(projectId);
        a.setDatasourceId(datasourceId);
        a.setQualifiedName(qualifiedName);
        a.setName(str(payload.get("name")));
        a.setDescription(str(payload.get("description")));
        a.setOwnerId(longOrNull(payload.get("ownerId")));
        a.setStewardId(longOrNull(payload.get("stewardId")));
        a.setGlossaryTerms(glossaryJson(payload.get("glossaryTerms")));
        a.setSensitivity(Sensitivity.parseOrDefault(str(payload.get("sensitivity"))).name());
        a.setSchemaSnapshotJson(str(payload.get("schemaSnapshotJson")));
        a.setLineageTableRef(str(payload.get("lineageTableRef")));
        a.setStatus("ACTIVE");
        a.setCreatedBy(userId);
        a.setUpdatedBy(userId);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        a.setDeleted(0);
        a.setVersion(0);
        DataAsset saved = repository.save(a);
        metrics.recordWrite("asset_create");
        return saved;
    }

    /** PATCH 语义更新（含键=改；显式 null=清空；缺键=不改）。schema 漂移 → ASSET_CHANGED(schema)。 */
    @Transactional
    public DataAsset update(long tenantId, long userId, long id, Map<String, Object> patch) {
        DataAsset a = require(tenantId, id);
        String oldSchema = a.getSchemaSnapshotJson();

        if (patch.containsKey("name")) a.setName(str(patch.get("name")));
        if (patch.containsKey("description")) a.setDescription(str(patch.get("description")));
        if (patch.containsKey("ownerId")) a.setOwnerId(longOrNull(patch.get("ownerId")));
        if (patch.containsKey("stewardId")) a.setStewardId(longOrNull(patch.get("stewardId")));
        if (patch.containsKey("glossaryTerms")) a.setGlossaryTerms(glossaryJson(patch.get("glossaryTerms")));
        if (patch.containsKey("sensitivity")) a.setSensitivity(Sensitivity.parseOrDefault(str(patch.get("sensitivity"))).name());
        if (patch.containsKey("schemaSnapshotJson")) a.setSchemaSnapshotJson(str(patch.get("schemaSnapshotJson")));
        if (patch.containsKey("lineageTableRef")) a.setLineageTableRef(str(patch.get("lineageTableRef")));
        if (patch.containsKey("status")) a.setStatus(normalizeStatus(str(patch.get("status"))));

        a.setUpdatedBy(userId);
        a.setUpdatedAt(LocalDateTime.now());
        DataAsset saved = repository.save(a);
        metrics.recordWrite("asset_update");

        if (patch.containsKey("schemaSnapshotJson") && !Objects.equals(oldSchema, saved.getSchemaSnapshotJson())) {
            subscriptionService.notifyChange(tenantId, "ASSET", id, "schema",
                    Map.of("assetName", saved.getName() == null ? saved.getQualifiedName() : saved.getName()));
        }
        return saved;
    }

    /** 下线（RETIRED）。 */
    @Transactional
    public DataAsset retire(long tenantId, long userId, long id) {
        DataAsset a = require(tenantId, id);
        a.setStatus("RETIRED");
        a.setUpdatedBy(userId);
        a.setUpdatedAt(LocalDateTime.now());
        DataAsset saved = repository.save(a);
        metrics.recordWrite("asset_retire");
        return saved;
    }

    /**
     * schema 对账（FR-010 / 场景8）：底层表删/改名 → STALE + ASSET_CHANGED(schema)；恢复 → ACTIVE。
     *
     * <p>判据：资产的血缘表锚点 {@code lineage_table_ref} 存在性。PG 血缘四表（data_table）已随
     * 血缘退役（0.0.2）删除、neo4j 成单一底座；底层表节点的物理存在性校验在 neo4j 表节点读能力
     * 落地后接入（届时改为 reader 查询 + 离线降级），当前以血缘锚点缺失（ref 为空）判 STALE。
     */
    @Transactional
    public DataAsset reconcile(long tenantId, long userId, long id) {
        DataAsset a = require(tenantId, id);
        if ("RETIRED".equals(a.getStatus())) return a;
        String ref = a.getLineageTableRef();
        boolean exists = ref != null && !ref.isBlank();
        String target = exists ? "ACTIVE" : "STALE";
        if (!target.equals(a.getStatus())) {
            a.setStatus(target);
            a.setUpdatedBy(userId);
            a.setUpdatedAt(LocalDateTime.now());
            a = repository.save(a);
            if ("STALE".equals(target)) {
                subscriptionService.notifyChange(tenantId, "ASSET", id, "schema",
                        Map.of("reason", "underlying_table_missing", "qualifiedName", a.getQualifiedName()));
            }
        }
        return a;
    }

    // ─── 读 ────────────────────────────────────────────────────

    /** 详情（PII 仅 owner/steward 可见 → 否则 catalog.forbidden_sensitivity）。 */
    public AssetDtos.AssetDetail getDetail(long tenantId, long id, long callerUserId) {
        DataAsset a = require(tenantId, id);
        enforceSensitivity(a, callerUserId);
        return new AssetDtos.AssetDetail(
                a.getId(), a.getDatasourceId(), a.getQualifiedName(), a.getName(), a.getDescription(),
                a.getOwnerId(), a.getStewardId(), parseGlossary(a.getGlossaryTerms()), a.getSensitivity(),
                a.getSchemaSnapshotJson(), a.getLineageTableRef(), a.getStatus(), tagsOf(id),
                a.getCreatedAt(), a.getUpdatedAt());
    }

    /** 取实体（血缘/质量端点用；含 PII 可见性校验）。 */
    public DataAsset requireVisible(long tenantId, long id, long callerUserId) {
        DataAsset a = require(tenantId, id);
        enforceSensitivity(a, callerUserId);
        return a;
    }

    private void enforceSensitivity(DataAsset a, long callerUserId) {
        if ("PII".equals(a.getSensitivity())
                && !isOwnerOrSteward(a, callerUserId)) {
            throw new BizException("catalog.forbidden_sensitivity").withHttpStatus(403);
        }
    }

    private boolean isOwnerOrSteward(DataAsset a, long callerUserId) {
        return (a.getOwnerId() != null && a.getOwnerId() == callerUserId)
                || (a.getStewardId() != null && a.getStewardId() == callerUserId);
    }

    private DataAsset require(long tenantId, long id) {
        return repository.findByIdAndTenantIdAndDeleted(id, tenantId, 0)
                .orElseThrow(() -> new BizException("catalog.asset_not_found", id).withHttpStatus(404));
    }

    private List<String> tagsOf(long assetId) {
        return jdbc.query("SELECT t.name FROM entity_tag et JOIN tag t ON et.tag_id = t.id "
                        + "WHERE et.entity_type = 'ASSET' AND et.entity_id = ?",
                (rs, i) -> rs.getString(1), assetId);
    }

    // ─── helpers ───────────────────────────────────────────────

    private List<String> parseGlossary(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private String glossaryJson(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s;
        if (v instanceof List<?> list) {
            try {
                return objectMapper.writeValueAsString(list);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return String.valueOf(v);
    }

    private static String normalizeStatus(String s) {
        if (s == null) return "ACTIVE";
        String up = s.trim().toUpperCase();
        return switch (up) {
            case "ACTIVE", "STALE", "RETIRED" -> up;
            default -> "ACTIVE";
        };
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long longOr(Object o, long def) {
        Long v = longOrNull(o);
        return v == null ? def : v;
    }

    private static Long longOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
