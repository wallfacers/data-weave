package com.dataweave.master.infrastructure;

import com.dataweave.master.i18n.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * {@link ChatFileStorage} 的本地文件实现（all-in-one / H2 零依赖模式默认）。
 *
 * <p>当 {@code chatfile.storage.type} 未配置或为 {@code local} 时装配（{@code matchIfMissing=true}）。
 * 存入 {@code chatfile.local.dir}（默认 {@code data/chat-files}，应加入 .gitignore）。
 * 保证「克隆即跑、CI 零外部依赖」——无需 MinIO 即可测试聊天附件上传/下载链路。
 */
@Component
@ConditionalOnProperty(name = "chatfile.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalChatFileStorage implements ChatFileStorage {

    private final Path dir;

    public LocalChatFileStorage(@Value("${chatfile.local.dir:data/chat-files}") String dir) {
        this.dir = Paths.get(dir);
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new BizException("chat.file.store_failed",
                    "无法创建聊天附件本地目录 " + this.dir.toAbsolutePath() + ": " + e.getMessage())
                    .withHttpStatus(500);
        }
    }

    @Override
    public String put(String storageKey, byte[] content) {
        try {
            Files.write(dir.resolve(storageKey), content);
            return storageKey;
        } catch (IOException e) {
            throw new BizException("chat.file.store_failed", storageKey).withHttpStatus(500);
        }
    }

    @Override
    public byte[] get(String storageKey) {
        try {
            return Files.readAllBytes(dir.resolve(storageKey));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new BizException("chat.file.read_failed", storageKey).withHttpStatus(500);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(dir.resolve(storageKey));
        } catch (IOException e) {
            throw new BizException("chat.file.delete_failed", storageKey).withHttpStatus(500);
        }
    }

    @Override
    public String type() {
        return "LOCAL";
    }
}
