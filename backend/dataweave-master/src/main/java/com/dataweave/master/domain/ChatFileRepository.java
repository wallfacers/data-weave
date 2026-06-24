package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

/**
 * 聊天附件文件元数据仓储（chat-attachments）。主键为内容 sha256。
 */
public interface ChatFileRepository extends CrudRepository<ChatFile, String> {
}
