package com.dataweave.master.infrastructure;

/**
 * 聊天附件字节存储后端抽象（chat-attachments）。
 *
 * <p>按 {@code storageKey}（内容寻址，通常为 sha256）存取附件原始字节。
 * 默认实现 {@link LocalChatFileStorage}（all-in-one / H2 零依赖模式落本地盘，保证克隆即跑）。
 * 与驱动 jar 的 {@link DriverJarStorage} 同构，但隔离命名空间（不同目录/bucket），互不污染。
 */
public interface ChatFileStorage {

    /** 存入字节，返回存储 key。 */
    String put(String storageKey, byte[] content);

    /** 读取字节；不存在返回 null。 */
    byte[] get(String storageKey);

    /** 删除；不存在视为成功。 */
    void delete(String storageKey);

    /** 后端类型标识（{@code LOCAL} / {@code MINIO}），记入 agent_chat_file.storage_type。 */
    String type();
}
