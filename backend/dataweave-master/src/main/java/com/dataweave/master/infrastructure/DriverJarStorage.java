package com.dataweave.master.infrastructure;

/**
 * 驱动 jar 字节存储后端抽象（datasource-driver-isolation）。
 *
 * <p>实现：{@link MinioDriverJarStorage}（distributed 模式，复用 S3LogArchive 的 MinIO 基建）
 * 与 {@link LocalDriverJarStorage}（all-in-one / H2 零依赖模式降级）。
 * 按 {@code storageKey}（通常为 {@code sha256.jar}）存取原始 jar 字节。
 */
public interface DriverJarStorage {

    /** 存入 jar 字节，返回存储 key（实现可基于内容哈希生成）。 */
    String put(String storageKey, byte[] content);

    /** 读取 jar 字节；不存在返回 null。 */
    byte[] get(String storageKey);

    /** 删除；不存在视为成功。 */
    void delete(String storageKey);

    /** 后端类型标识（{@code MINIO} / {@code LOCAL}），用于 driver_jars.storage_type 记录。 */
    String type();
}
