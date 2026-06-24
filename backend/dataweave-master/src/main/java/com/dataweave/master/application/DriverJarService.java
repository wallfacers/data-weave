package com.dataweave.master.application;

import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.infrastructure.DriverJarStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static com.dataweave.master.application.DatasourceDtos.DriverJarVO;

/**
 * 驱动 jar 资产服务（datasource-driver-isolation）。
 *
 * <p>上传链路：校验 {@code .jar} → 解析 {@code META-INF/services/java.sql.Driver} 取驱动类 →
 * 计算 sha256 并租户内去重 → 存储后端（MinIO/本地）→ 建 {@code driver_jars} 资产（MVP 直通 ACTIVE）。
 *
 * <p>安全：design D5 经 apply 阶段调整为 MVP 直通（校验 + 日志审计先行，PolicyEngine L2 审批闭环标 TODO）。
 */
@Service
public class DriverJarService {

    private static final Logger log = LoggerFactory.getLogger(DriverJarService.class);
    private static final String DRIVER_SERVICE_ENTRY = "META-INF/services/java.sql.Driver";

    private final DriverJarRepository driverJarRepository;
    private final DatasourceRepository datasourceRepository;
    private final DriverJarStorage storage;

    public DriverJarService(DriverJarRepository driverJarRepository,
                            DatasourceRepository datasourceRepository,
                            DriverJarStorage storage) {
        this.driverJarRepository = driverJarRepository;
        this.datasourceRepository = datasourceRepository;
        this.storage = storage;
    }

    /** 上传驱动 jar：校验 + 去重 + 存储 + 建资产（MVP 直通 ACTIVE）。 */
    public DriverJarVO upload(String typeCode, String originalName, byte[] bytes, Long tenantId) {
        if (originalName == null || !originalName.toLowerCase().endsWith(".jar")) {
            throw new BizException("datasource.driver_not_jar", originalName);
        }
        if (bytes == null || bytes.length == 0) {
            throw new BizException("datasource.driver_empty");
        }
        String driverClass = detectDriverClass(bytes);
        if (driverClass == null) {
            throw new BizException("datasource.driver_no_jdbc_impl", originalName);
        }

        String sha256 = sha256(bytes);
        // 租户内 sha256 去重复用
        Optional<DriverJar> existing = driverJarRepository.findByTenantIdAndSha256AndDeleted(tenantId, sha256, 0);
        if (existing.isPresent()) {
            log.info("驱动 jar 命中去重：id={} sha256={}", existing.get().getId(), sha256);
            return toVO(existing.get());
        }

        String storageKey = sha256 + ".jar";
        storage.put(storageKey, bytes);

        DriverJar jar = new DriverJar();
        jar.setTenantId(tenantId);
        jar.setTypeCode(typeCode);
        jar.setDriverClass(driverClass);
        jar.setOriginalName(originalName);
        jar.setSha256(sha256);
        jar.setStorageType(storage.type());
        jar.setStorageKey(storageKey);
        jar.setSizeBytes((long) bytes.length);
        jar.setStatus("ACTIVE");
        jar.setDeleted(0);
        jar.setVersion(0);
        DriverJar saved = driverJarRepository.save(jar);
        log.info("驱动 jar 上传（MVP 直通 ACTIVE）：id={} type={} sha256={} driver={} size={}",
                saved.getId(), typeCode, sha256, driverClass, bytes.length);
        return toVO(saved);
    }

    /** 列出某类型下所有 ACTIVE 资产。 */
    public List<DriverJarVO> listByType(String typeCode) {
        return driverJarRepository.findByTypeCodeAndStatusAndDeletedOrderByCreatedAtDesc(typeCode, "ACTIVE", 0)
                .stream()
                .map(this::toVO)
                .toList();
    }

    /** 删除资产；被数据源引用时拒绝。 */
    public void delete(Long id) {
        DriverJar jar = driverJarRepository.findById(id)
                .filter(j -> j.getDeleted() == null || j.getDeleted() == 0)
                .orElseThrow(() -> new BizException("datasource.driver_jar_not_found", id).withHttpStatus(404));
        long ref = datasourceRepository.countByDriverJarIdAndDeleted(id, 0);
        if (ref > 0) {
            throw new BizException("datasource.driver_jar_in_use", ref).withHttpStatus(409);
        }
        storage.delete(jar.getStorageKey());
        jar.setDeleted(1);
        driverJarRepository.save(jar);
        log.info("驱动 jar 删除：id={} sha256={}", id, jar.getSha256());
    }

    /** 解析 jar 内 META-INF/services/java.sql.Driver，取首个非注释驱动类名；无则 null。 */
    private String detectDriverClass(byte[] bytes) {
        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(bytes))) {
            JarEntry e;
            while ((e = jis.getNextJarEntry()) != null) {
                if (DRIVER_SERVICE_ENTRY.equals(e.getName())) {
                    String text = new String(jis.readAllBytes(), StandardCharsets.UTF_8);
                    jis.closeEntry();
                    for (String line : text.split("\\R")) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            return line;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("解析 jar 驱动入口失败: {}", ex.getMessage());
        }
        return null;
    }

    private String sha256(byte[] bytes) {
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

    private DriverJarVO toVO(DriverJar j) {
        String sha = j.getSha256();
        String shortSha = (sha != null && sha.length() > 12) ? sha.substring(0, 12) : sha;
        return new DriverJarVO(j.getId(), j.getTypeCode(), j.getDriverClass(), j.getOriginalName(),
                shortSha, j.getStorageType(), j.getSizeBytes(), j.getStatus());
    }
}
