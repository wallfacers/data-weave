package com.dataweave.master.application.asset;

import com.dataweave.master.application.MetricService;
import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.asset.MetricListing;
import com.dataweave.master.domain.asset.MetricListingRepository;
import com.dataweave.master.domain.asset.MetricReuseRef;
import com.dataweave.master.domain.asset.MetricReuseRefRepository;
import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 指标市场：上架/认证/复用（防环）/详情/搜索（FR-004/005/006）。
 * 复用现有 metrics 定义（不复制口径，D3）；复用引用做有向可达性防环（catalog.reuse_cycle）。
 */
@Service
public class MetricListingService {

    private static final Logger log = LoggerFactory.getLogger(MetricListingService.class);

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final MetricListingRepository listingRepository;
    private final MetricReuseRefRepository reuseRepository;
    private final MetricService metricService;
    private final JdbcTemplate jdbc;
    private final CatalogMetrics metrics;

    public MetricListingService(MetricListingRepository listingRepository,
                                MetricReuseRefRepository reuseRepository,
                                MetricService metricService,
                                JdbcTemplate jdbc,
                                CatalogMetrics metrics) {
        this.listingRepository = listingRepository;
        this.reuseRepository = reuseRepository;
        this.metricService = metricService;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    // ─── 写 ────────────────────────────────────────────────────

    /** 上架（幂等：已存在则更新并复位 LISTED）。payload: metricType/metricId/metricCode/ownerId/description/freshnessInfo。 */
    @Transactional
    public MetricListing list(long tenantId, long projectId, long userId, Map<String, Object> payload) {
        String metricType = upper(str(payload.get("metricType")), "ATOMIC");
        Long metricId = longOrNull(payload.get("metricId"));
        if (metricId == null) {
            throw new BizException("catalog.listing_invalid").withHttpStatus(400);
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<MetricListing> existing = listingRepository
                .findFirstByTenantIdAndProjectIdAndMetricTypeAndMetricIdAndDeleted(tenantId, projectId, metricType, metricId, 0);
        MetricListing m = existing.orElseGet(MetricListing::new);
        if (m.getId() == null) {
            m.setTenantId(tenantId);
            m.setProjectId(projectId);
            m.setMetricType(metricType);
            m.setMetricId(metricId);
            m.setCertification("NONE");
            m.setCreatedBy(userId);
            m.setCreatedAt(now);
            m.setDeleted(0);
            m.setVersion(0);
        }
        m.setMetricCode(str(payload.get("metricCode")));
        m.setOwnerId(longOrNull(payload.getOrDefault("ownerId", userId)));
        m.setDescription(str(payload.get("description")));
        m.setFreshnessInfo(str(payload.get("freshnessInfo")));
        m.setStatus("LISTED");
        m.setUpdatedBy(userId);
        m.setUpdatedAt(now);
        MetricListing saved = listingRepository.save(m);
        metrics.recordWrite("metric_list");
        return saved;
    }

    /** 认证（可信徽章，受控 L2）。DELISTED 不可认证 → catalog.not_certifiable。 */
    @Transactional
    public MetricListing certify(long tenantId, long userId, long id) {
        MetricListing m = require(tenantId, id);
        if ("DELISTED".equals(m.getStatus())) {
            throw new BizException("catalog.not_certifiable").withHttpStatus(409);
        }
        m.setCertification("CERTIFIED");
        m.setCertifiedBy(userId);
        m.setCertifiedAt(LocalDateTime.now());
        m.setUpdatedBy(userId);
        m.setUpdatedAt(LocalDateTime.now());
        MetricListing saved = listingRepository.save(m);
        metrics.recordWrite("metric_certify");
        return saved;
    }

    @Transactional
    public MetricListing delist(long tenantId, long userId, long id) {
        MetricListing m = require(tenantId, id);
        m.setStatus("DELISTED");
        m.setUpdatedBy(userId);
        m.setUpdatedAt(LocalDateTime.now());
        MetricListing saved = listingRepository.save(m);
        metrics.recordWrite("metric_delist");
        return saved;
    }

    /**
     * 复用（建引用，防环）。语义：path 指标被 consumer 复用（consumer 依赖 path）。
     * 防环：若 path →* consumer 已可达（加边后成环）→ catalog.reuse_cycle。
     */
    @Transactional
    public MetricReuseRef reuse(long tenantId, long projectId, long userId, long pathListingId,
                               String consumerType, String consumerRef) {
        require(tenantId, pathListingId); // 校验被复用指标存在 + 租户隔离
        String ct = upper(consumerType, "METRIC");
        if (consumerRef == null || consumerRef.isBlank()) {
            throw new BizException("catalog.reuse_invalid").withHttpStatus(400);
        }
        // 仅指标间复用参与 listing 空间防环；TASK/ASSET 复用指标不成环。
        if ("METRIC".equals(ct)) {
            String s = consumerRef.trim();
            String t = String.valueOf(pathListingId);
            if (s.equals(t) || reachable(tenantId, projectId, t, s)) {
                throw new BizException("catalog.reuse_cycle", s, t).withHttpStatus(409);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        MetricReuseRef ref = new MetricReuseRef();
        ref.setTenantId(tenantId);
        ref.setProjectId(projectId);
        ref.setListingId(pathListingId);
        ref.setConsumerType(ct);
        ref.setConsumerRef(consumerRef.trim());
        ref.setCreatedBy(userId);
        ref.setCreatedAt(now);
        ref.setDeleted(0);
        ref.setVersion(0);
        MetricReuseRef saved = reuseRepository.save(ref);
        metrics.recordWrite("metric_reuse");
        return saved;
    }

    /** 有向可达性：from →* to（沿「消费者→被复用指标」依赖边）。 */
    private boolean reachable(long tenantId, long projectId, String from, String to) {
        // 邻接：consumer_ref(node) → listing_id(node)（消费者依赖被复用指标）
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (MetricReuseRef r : reuseRepository.findByTenantIdAndProjectIdAndDeleted(tenantId, projectId, 0)) {
            if (r.getConsumerRef() == null || r.getListingId() == null) continue;
            adj.computeIfAbsent(r.getConsumerRef().trim(), k -> new ArrayList<>())
                    .add(String.valueOf(r.getListingId()));
        }
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            String n = stack.pop();
            if (!seen.add(n)) continue;
            for (String next : adj.getOrDefault(n, List.of())) {
                if (next.equals(to)) return true;
                stack.push(next);
            }
        }
        return false;
    }

    // ─── 读 ────────────────────────────────────────────────────

    public AssetDtos.ListingDetail getDetail(long tenantId, long id) {
        MetricListing m = require(tenantId, id);
        Map<String, Object> definition = loadDefinition(m);
        int reuseCount = reuseRepository
                .findByTenantIdAndProjectIdAndListingIdAndDeleted(tenantId, m.getProjectId(), id, 0).size();
        return new AssetDtos.ListingDetail(
                m.getId(), m.getMetricType(), m.getMetricId(), m.getMetricCode(), m.getOwnerId(),
                m.getCertification(), m.getCertifiedBy(), m.getCertifiedAt(), m.getFreshnessInfo(),
                m.getDescription(), m.getStatus(), definition, reuseCount);
    }

    /** 复用现有 metric 定义（ATOMIC 经 MetricService；DERIVED/不可达 → 空定义，降级安全）。 */
    private Map<String, Object> loadDefinition(MetricListing m) {
        Map<String, Object> def = new LinkedHashMap<>();
        try {
            if ("ATOMIC".equals(m.getMetricType()) && m.getMetricCode() != null) {
                Optional<AtomicMetric> am = metricService.findLatestByCode(m.getMetricCode());
                am.ifPresent(x -> {
                    def.put("code", x.getCode());
                    def.put("name", x.getName());
                    def.put("measureExpr", x.getMeasureExpr());
                    def.put("aggType", x.getAggType());
                    def.put("unit", x.getUnit());
                    def.put("versionNo", x.getVersionNo());
                    def.put("status", x.getStatus());
                });
            }
        } catch (RuntimeException e) {
            log.debug("Metric definition load degraded for listing {}: {}", m.getId(), e.toString());
        }
        return def;
    }

    public AssetDtos.ListingSearchResult search(long tenantId, long projectId, String keyword,
                                                String certification, int page, int size) {
        StringBuilder where = new StringBuilder(
                "FROM metric_listing WHERE tenant_id = ? AND project_id = ? AND deleted = 0 AND status <> 'DELISTED'");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(projectId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            where.append(" AND (LOWER(metric_code) LIKE CONCAT('%', ?, '%') OR LOWER(description) LIKE CONCAT('%', ?, '%'))");
            args.add(kw);
            args.add(kw);
        }
        if (certification != null && !certification.isBlank()) {
            where.append(" AND certification = ?");
            args.add(certification.trim().toUpperCase());
        }

        Long totalObj = jdbc.queryForObject("SELECT COUNT(*) " + where, Long.class, args.toArray());
        long total = totalObj == null ? 0L : totalObj;

        int sz = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int pg = page <= 0 ? 1 : page;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz);
        pageArgs.add((pg - 1) * sz);
        List<AssetDtos.ListingSummary> items = jdbc.query(
                "SELECT id, metric_type, metric_id, metric_code, owner_id, certification, status, freshness_info "
                        + where + " ORDER BY certification DESC, updated_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new AssetDtos.ListingSummary(
                        rs.getLong("id"), rs.getString("metric_type"), rs.getLong("metric_id"),
                        rs.getString("metric_code"), (Long) rs.getObject("owner_id"),
                        rs.getString("certification"), rs.getString("status"), rs.getString("freshness_info")),
                pageArgs.toArray());

        Map<String, Map<String, Long>> facets = new LinkedHashMap<>();
        facets.put("certification", facet(where.toString(), args.toArray(), "certification"));
        facets.put("metric_type", facet(where.toString(), args.toArray(), "metric_type"));
        return new AssetDtos.ListingSearchResult(items, total, facets, false);
    }

    private Map<String, Long> facet(String where, Object[] args, String dim) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT " + dim + " AS k, COUNT(*) AS c " + where + " GROUP BY " + dim + " ORDER BY c DESC",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    Object k = rs.getObject("k");
                    out.put(k == null ? "" : String.valueOf(k), rs.getLong("c"));
                }, args);
        return out;
    }

    private MetricListing require(long tenantId, long id) {
        return listingRepository.findByIdAndTenantIdAndDeleted(id, tenantId, 0)
                .orElseThrow(() -> new BizException("catalog.metric_listing_not_found", id).withHttpStatus(404));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String upper(String s, String def) {
        if (s == null || s.isBlank()) return def;
        return s.trim().toUpperCase();
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
