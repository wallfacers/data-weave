package com.dataweave.master.application;

import com.dataweave.master.domain.ChatFile;
import com.dataweave.master.domain.ChatFileRepository;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.infrastructure.ChatFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 聊天附件文件服务（chat-attachments）：上传（去重 + 存储 + 元数据）与下载。
 *
 * <p>上传链路：校验非空/大小 → 计算 sha256 内容寻址（命中即复用，天然去重）→ 存字节到
 * {@link ChatFileStorage} → 建 {@code agent_chat_file} 元数据行。下载按 id（=sha256）取元数据 + 字节。
 *
 * <p>仅是把用户文件托管给 Agent 引用，不触碰平台状态，故不过 PolicyEngine 闸门（与驱动 jar 上传同级）。
 */
@Service
public class ChatFileService {

    private static final Logger log = LoggerFactory.getLogger(ChatFileService.class);

    /** 单文件上限 10MB——聊天附件（日志/CSV/SQL 片段）足够，挡住误传大文件。 */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final ChatFileRepository repository;
    private final ChatFileStorage storage;

    public ChatFileService(ChatFileRepository repository, ChatFileStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /** 上传附件：校验 + sha256 去重 + 存储 + 建元数据。返回前端可引用的轻量 VO。 */
    public ChatFileVO upload(String originalName, String mimeType, byte[] bytes, Long tenantId, Long userId) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException("chat.file.empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BizException("chat.file.too_large", MAX_BYTES / (1024 * 1024));
        }
        String name = (originalName == null || originalName.isBlank()) ? "file" : originalName;
        String sha256 = sha256(bytes);

        // 内容寻址去重：sha 命中即复用既有元数据，不重复落盘。
        ChatFile existing = repository.findById(sha256).orElse(null);
        if (existing != null) {
            log.info("聊天附件命中去重：id={} name={}", sha256, name);
            return toVO(existing);
        }

        String storageKey = sha256;
        // 先落盘再写库：若 put 成功但 save 失败，文件在存储中成为孤儿；
        // 但 sha256 内容寻址保证下次同内容上传会命中 dedup 并复用该文件，
        // 属最终一致——不引入分布式事务的复杂度。
        storage.put(storageKey, bytes);

        ChatFile f = new ChatFile();
        f.setId(sha256);
        f.setTenantId(tenantId != null ? tenantId : 1L);
        f.setOriginalName(name);
        f.setMimeType(mimeType);
        f.setSizeBytes((long) bytes.length);
        f.setStorageType(storage.type());
        f.setStorageKey(storageKey);
        f.setCreatedBy(userId);
        f.setCreatedAt(LocalDateTime.now());
        ChatFile saved = repository.save(f.markNew());
        log.info("聊天附件上传：id={} name={} size={}", sha256, name, bytes.length);
        return toVO(saved);
    }

    /** 按 id（=sha256）取附件元数据 + 字节（下载）。不存在抛 404。 */
    public Loaded load(String id) {
        ChatFile f = repository.findById(id)
                .orElseThrow(() -> new BizException("chat.file.not_found", id).withHttpStatus(404));
        byte[] bytes = storage.get(f.getStorageKey());
        if (bytes == null) {
            throw new BizException("chat.file.not_found", id).withHttpStatus(404);
        }
        return new Loaded(toVO(f), bytes);
    }

    private ChatFileVO toVO(ChatFile f) {
        return new ChatFileVO(f.getId(), f.getOriginalName(), f.getMimeType(),
                f.getSizeBytes() != null ? f.getSizeBytes() : 0L);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败", e);
        }
    }

    /** 前端可引用的附件元数据（id=sha256 作为附件引用键）。 */
    public record ChatFileVO(String id, String name, String mime, long size) {}

    /** 下载结果：元数据 + 字节。 */
    public record Loaded(ChatFileVO meta, byte[] bytes) {}
}
