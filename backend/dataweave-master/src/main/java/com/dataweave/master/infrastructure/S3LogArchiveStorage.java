package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.LogArchiveStorage;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * {@link LogArchiveStorage} 的 S3 实现（distributed 模式默认）：归档到 MinIO（S3 API 兼容）。
 *
 * <p>归档键约定 {@code logs/{biz_date}/{instance_id}/{attempt}.log}。all-in-one 模式由本地文件实现替换
 * （{@code logarchive.type=file}）。MinIO 服务端通过 docker-compose 启动，配置项 {@code logarchive.s3.*}。
 */
@Component
@ConditionalOnProperty(name = "logarchive.type", havingValue = "s3")
public class S3LogArchiveStorage implements LogArchiveStorage {

    private static final Logger log = LoggerFactory.getLogger(S3LogArchiveStorage.class);

    private final MinioClient minioClient;
    private final String bucket;

    public S3LogArchiveStorage(
            @Value("${logarchive.s3.endpoint:http://localhost:9000}") String endpoint,
            @Value("${logarchive.s3.access-key:minioadmin}") String accessKey,
            @Value("${logarchive.s3.secret-key:minioadmin}") String secretKey,
            @Value("${logarchive.s3.bucket:dataweave-logs}") String bucket) {
        this.bucket = bucket;
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        ensureBucket();
    }

    @Override
    public void put(String key, String content) {
        try {
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            try (InputStream stream = new ByteArrayInputStream(bytes)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(stream, bytes.length, -1)
                        .contentType("text/plain; charset=utf-8")
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException("S3 归档写入失败：" + key, e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build())) {
            return Optional.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (ErrorResponseException e) {
            if (e.errorResponse().code().equals("NoSuchKey")) {
                return Optional.empty();
            }
            throw new RuntimeException("S3 归档读取失败：" + key, e);
        } catch (Exception e) {
            throw new RuntimeException("S3 归档读取失败：" + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if (e.errorResponse().code().equals("NoSuchKey")) {
                return false;
            }
            throw new RuntimeException("S3 归档检查失败：" + key, e);
        } catch (Exception e) {
            throw new RuntimeException("S3 归档检查失败：" + key, e);
        }
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucket)
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucket)
                        .build());
                log.info("创建 S3 归档桶：{}", bucket);
            }
        } catch (Exception e) {
            log.warn("检查/创建 S3 归档桶失败（将在首次写入时重试）：{}", e.getMessage());
        }
    }
}
