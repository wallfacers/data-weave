package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.LogArchiveStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link LogArchiveStorage} 的本地文件实现（all-in-one 默认）：归档键映射为基目录下的相对路径文件。
 *
 * <p>键约定 {@code logs/{biz_date}/{instance_id}/{attempt}.log}。distributed 模式由 MinIO（S3）实现替换
 * （{@code logarchive.type=s3}）。
 */
@Component
@ConditionalOnProperty(name = "logarchive.type", havingValue = "file", matchIfMissing = true)
public class FileLogArchiveStorage implements LogArchiveStorage {

    private final Path baseDir;

    public FileLogArchiveStorage(
            @Value("${logarchive.file.base-dir:${java.io.tmpdir}/dataweave-log-archive}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    @Override
    public void put(String key, String content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("归档写入失败：" + key, e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        Path target = resolve(key);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("归档读取失败：" + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    /** 将归档键安全解析为基目录下的路径，防目录穿越。 */
    private Path resolve(String key) {
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir.normalize())) {
            throw new IllegalArgumentException("非法归档键：" + key);
        }
        return resolved;
    }
}
