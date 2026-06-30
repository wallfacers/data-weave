package com.dataweave.master.domain.lineage;

/**
 * 数据源去重身份（FR-004）。物理坐标去重；凭据不进键；缺坐标走降级身份。
 *
 * <p>身份键 {@link #dsKey()}：
 * <ul>
 *   <li>有坐标（ip 与 database 同时非空）→ {@code tenantId|ip|port|database}</li>
 *   <li>缺坐标 → {@code tenantId|datasource:<fallbackName>}（确定性降级，仍唯一不重复）</li>
 * </ul>
 * 规范化：ip/database 小写 trim；凭据（username/password）不进键——同物理库不同凭据归一到同一节点（SC-002）。
 * 同 ip/port 不同 database → 不同键 → 不同节点（验收 #2）。tenantId 进键保证跨租户隔离；projectId 不进键
 * （同一租户内同一物理库跨项目共享单一 :Datasource 结构节点）。
 *
 * <p>{@code port} 由调用方在装配时按 {@code datasource_type.default_port} 补缺省；本 record 只承载已解析值。
 */
public record DatasourceCoord(
        long tenantId,
        long projectId,
        String ip,          // host，小写 trim；缺则 null（走 fallbackName）
        Integer port,       // 缺省由调用方补 datasource_type.default_port；缺则 null
        String database,    // 小写 trim；缺则 null
        String fallbackName // 缺连接坐标时的确定性降级名（如 datasource 配置 name 或 id）
) {
    /** 规范化合成唯一键 dsKey。 */
    public String dsKey() {
        String normIp = ip == null ? null : ip.trim().toLowerCase();
        String normDb = database == null ? null : database.trim().toLowerCase();
        if (normIp == null || normDb == null) {
            String fb = fallbackName == null ? "" : fallbackName.trim();
            return tenantId + "|datasource:" + fb;
        }
        String normPort = port == null ? "" : port.toString();
        return tenantId + "|" + normIp + "|" + normPort + "|" + normDb;
    }
}
