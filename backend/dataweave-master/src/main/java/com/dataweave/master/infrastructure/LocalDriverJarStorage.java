package com.dataweave.master.infrastructure;

import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * {@link DriverJarStorage} 的本地文件实现（all-in-one / H2 零依赖模式默认）。
 *
 * <p>当 {@code driverjar.storage.type} 未配置或为 {@code local} 时装配（{@code matchIfMissing=true}）。
 * 存入 {@code driverjar.local.dir}（默认 {@code libs/jdbc}，应加入 .gitignore）。
 * 保证「克隆即跑、CI 零外部依赖」底线——无需 MinIO 即可测试上传/隔离加载链路。
 */
@Component
@ConditionalOnProperty(name = "driverjar.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalDriverJarStorage implements DriverJarStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDriverJarStorage.class);

    private final Path dir;

    public LocalDriverJarStorage(@Value("${driverjar.local.dir:libs/jdbc}") String dir) {
        this.dir = Paths.get(dir);
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            log.warn("创建驱动 jar 本地目录失败 {}: {}", this.dir.toAbsolutePath(), e.getMessage());
        }
    }

    @Override
    public String put(String storageKey, byte[] content) {
        try {
            Files.write(dir.resolve(storageKey), content);
            return storageKey;
        } catch (IOException e) {
            throw new BizException("datasource.driver.store_failed", storageKey).withHttpStatus(500);
        }
    }

    @Override
    public byte[] get(String storageKey) {
        try {
            return Files.readAllBytes(dir.resolve(storageKey));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new BizException("datasource.driver.read_failed", storageKey).withHttpStatus(500);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(dir.resolve(storageKey));
        } catch (IOException e) {
            throw new BizException("datasource.driver.delete_failed", storageKey).withHttpStatus(500);
        }
    }

    @Override
    public String type() {
        return "LOCAL";
    }
}
