package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.DatasourceDtos.DriverJarVO;
import com.dataweave.master.application.DriverJarService;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 驱动 jar 资产 REST 端点（datasource-driver-isolation）。
 *
 * <p>上传走 WebFlux 原生 multipart（{@link FilePart}，非 Spring MVC {@code MultipartFile}）。
 */
@RestController
@RequestMapping("/api")
public class DriverJarController {

    private final DriverJarService driverJarService;

    public DriverJarController(DriverJarService driverJarService) {
        this.driverJarService = driverJarService;
    }

    /** 上传驱动 jar（multipart）：file=jar, typeCode=适用类型。校验 + 去重 + 存储 + 直通 ACTIVE。 */
    @PostMapping(value = "/driver-jars", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<DriverJarVO>> upload(
            @RequestPart("file") FilePart file,
            @RequestPart("typeCode") String typeCode) {
        Long tenantId = currentTenantId();
        return DataBufferUtils.join(file.content())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .map(bytes -> ApiResponse.ok(
                        driverJarService.upload(typeCode, file.filename(), bytes, tenantId)));
    }

    /** 列出某 typeCode 下所有 ACTIVE 驱动 jar 资产。 */
    @GetMapping("/driver-jars")
    public ApiResponse<List<DriverJarVO>> list(@RequestParam String typeCode) {
        return ApiResponse.ok(driverJarService.listByType(typeCode));
    }

    /** 删除驱动 jar 资产；被数据源引用时返回 409。 */
    @DeleteMapping("/driver-jars/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        driverJarService.delete(id);
        return ApiResponse.ok(null);
    }

    private static Long currentTenantId() {
        Long tenantId = TenantContext.tenantId();
        return tenantId != null ? tenantId : 1L;
    }
}
