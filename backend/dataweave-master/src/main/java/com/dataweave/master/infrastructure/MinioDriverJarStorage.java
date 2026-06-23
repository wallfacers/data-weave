package com.dataweave.master.infrastructure;

import com.dataweave.master.i18n.BizException;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * {@link DriverJarStorage} 的 MinIO 实现（distributed 模式）。
 *
 * <p>配置项 {@code driverjar.s3.*}；当 {@code driverjar.storage.type=minio} 时装配。
 * 复用 docker-compose 起的 MinIO（S3 API 兼容），distributed 模式下各 worker JVM 自行从该 bucket 拉取 jar。
 * all-in-one / H2 零依赖模式由 {@link LocalDriverJarStorage} 替换（默认）。
 */
@Component
@ConditionalOnProperty(name = "driverjar.storage.type", havingValue = "minio")
public class MinioDriverJarStorage implements DriverJarStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioDriverJarStorage.class);

    private final MinioClient minioClient;
    private final String bucket;

    public MinioDriverJarStorage(
            @Value("${driverjar.s3.endpoint:http://localhost:9000}") String endpoint,
            @Value("${driverjar.s3.access-key:minioadmin}") String accessKey,
            @Value("${driverjar.s3.secret-key:minioadmin}") String secretKey,
            @Value("${driverjar.s3.bucket:dataweave-drivers}") String bucket) {
        this.bucket = bucket;
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        ensureBucket();
    }

    @Override
    public String put(String storageKey, byte[] content) {
        try (InputStream stream = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .stream(stream, content.length, -1)
                    .contentType("application/java-archive")
                    .build());
        } catch (Exception e) {
            throw failed("datasource.driver.store_failed", storageKey, e);
        }
        return storageKey;
    }

    @Override
    public byte[] get(String storageKey) {
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(storageKey)
                .build())) {
            return stream.readAllBytes();
        } catch (ErrorResponseException e) {
            if (e.errorResponse().code().equals("NoSuchKey")) {
                return null;
            }
            throw failed("datasource.driver.read_failed", storageKey, e);
        } catch (Exception e) {
            throw failed("datasource.driver.read_failed", storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .build());
        } catch (Exception e) {
            throw failed("datasource.driver.delete_failed", storageKey, e);
        }
    }

    @Override
    public String type() {
        return "MINIO";
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("创建驱动 jar 桶：{}", bucket);
            }
        } catch (Exception e) {
            log.warn("检查/创建驱动 jar 桶失败（将在首次写入时重试）：{}", e.getMessage());
        }
    }

    private static BizException failed(String code, String key, Exception cause) {
        BizException ex = new BizException(code, key).withHttpStatus(500);
        ex.initCause(cause);
        return ex;
    }
}
