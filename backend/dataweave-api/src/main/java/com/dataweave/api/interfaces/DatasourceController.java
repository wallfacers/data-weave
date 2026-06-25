package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ConnectionTesterFactory;
import com.dataweave.master.application.DatasourceDtos.*;
import com.dataweave.master.application.DatasourceService;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceType;
import com.dataweave.master.domain.DatasourceTypeRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 数据源管理 REST 端点：CRUD + 连通性测试 + 类型查询。
 */
@RestController
@RequestMapping("/api")
public class DatasourceController {

    private final DatasourceService datasourceService;
    private final DatasourceTypeRepository datasourceTypeRepository;
    private final ConnectionTesterFactory connectionTesterFactory;

    public DatasourceController(DatasourceService datasourceService,
                                 DatasourceTypeRepository datasourceTypeRepository,
                                 ConnectionTesterFactory connectionTesterFactory) {
        this.datasourceService = datasourceService;
        this.datasourceTypeRepository = datasourceTypeRepository;
        this.connectionTesterFactory = connectionTesterFactory;
    }

    // ===== Datasource Types =====

    @GetMapping("/datasource-types")
    public ApiResponse<List<DatasourceType>> listTypes(
            @RequestParam(required = false) String category) {
        List<DatasourceType> types;
        if (category != null && !category.isBlank()) {
            types = datasourceTypeRepository.findByCategoryAndDeletedOrderByCodeAsc(category, 0);
        } else {
            types = datasourceTypeRepository.findByDeletedOrderByCategoryAscCodeAsc(0);
        }
        return ApiResponse.ok(types);
    }

    // ===== Datasource CRUD =====

    @GetMapping("/datasources")
    public ApiResponse<Object> list(
            @RequestParam(defaultValue = "1") Long projectId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) String connectionStatus,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Long tenantId = currentTenantId();
        // 有筛选/分页参数 → 动态查询返回分页结果
        if (search != null || typeCode != null || connectionStatus != null || page != null) {
            List<String> typeCodes = (typeCode != null && !typeCode.isBlank())
                    ? Arrays.asList(typeCode.split(","))
                    : null;
            int p = page != null ? Math.max(1, page) : 1;
            int s = size != null ? Math.max(1, Math.min(size, 100)) : 20;
            return ApiResponse.ok(datasourceService.query(tenantId, projectId,
                    search, typeCodes, connectionStatus, p, s));
        }
        // 无参 → 旧版全量返回（向后兼容）
        return ApiResponse.ok(datasourceService.listByProject(tenantId, projectId));
    }

    @GetMapping("/datasources/{id}")
    public ApiResponse<DatasourceVO> get(@PathVariable Long id) {
        return ApiResponse.ok(datasourceService.getById(id));
    }

    @PostMapping("/datasources")
    public ApiResponse<DatasourceVO> create(@RequestBody DatasourceCreateRequest req) {
        Long tenantId = currentTenantId();
        return ApiResponse.ok(datasourceService.create(req, tenantId));
    }

    @PutMapping("/datasources/{id}")
    public ApiResponse<DatasourceVO> update(@PathVariable Long id,
                                             @RequestBody DatasourceUpdateRequest req) {
        return ApiResponse.ok(datasourceService.update(id, req));
    }

    @DeleteMapping("/datasources/{id}")
    public ApiResponse<DeleteResult> delete(@PathVariable Long id) {
        return ApiResponse.ok(datasourceService.delete(id));
    }

    /** 解绑数据源的上传驱动 jar（回退内置默认驱动）。 */
    @DeleteMapping("/datasources/{id}/driver-jar")
    public ApiResponse<DatasourceVO> unbindDriverJar(@PathVariable Long id) {
        return ApiResponse.ok(datasourceService.unbindDriverJar(id));
    }

    // ===== Connection Test =====

    @PostMapping("/datasources/{id}/test")
    public ApiResponse<ConnectionTestResult> testSaved(@PathVariable Long id,
            @RequestHeader(value = "Accept-Language", required = false, defaultValue = "zh-CN") String acceptLang) {
        Datasource ds = datasourceService.getEntityById(id);
        String decryptedPw = datasourceService.decryptPassword(ds);
        ConnectionTestResult result = connectionTesterFactory.test(ds, decryptedPw, resolveLocale(acceptLang));
        // 持久化连接状态（CONNECTED / DISCONNECTED）
        datasourceService.updateConnectionStatus(id, result.success());
        return ApiResponse.ok(result);
    }

    @PostMapping("/datasources/test")
    public ApiResponse<ConnectionTestResult> testConfig(
            @RequestBody DatasourceCreateRequest req,
            @RequestParam(required = false) Long datasourceId,
            @RequestHeader(value = "Accept-Language", required = false, defaultValue = "zh-CN") String acceptLang) {
        // Build a transient Datasource from request (not saved to DB)
        Datasource ds = new Datasource();
        ds.setName(req.name());
        ds.setTypeCode(req.typeCode());
        ds.setHost(req.host());
        ds.setPort(req.port());
        ds.setDatabaseName(req.databaseName());
        ds.setJdbcUrl(req.jdbcUrl());
        ds.setUsername(req.username());
        ds.setPropsJson(req.propsJson());

        // 密码：表单传了明文就用明文；为空且是编辑（有 datasourceId）→ 用已保存的密码
        String password = req.password();
        if ((password == null || password.isEmpty()) && datasourceId != null) {
            Datasource saved = datasourceService.getEntityById(datasourceId);
            password = datasourceService.decryptPassword(saved);
        }

        return ApiResponse.ok(connectionTesterFactory.test(ds, password, resolveLocale(acceptLang)));
    }

    /** 解析 Accept-Language 头（取首个 tag）为 Locale；空/异常兜底中文。 */
    private static Locale resolveLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        String tag = acceptLanguage.split(",")[0].trim();
        try {
            Locale locale = Locale.forLanguageTag(tag);
            return locale.getLanguage().isEmpty() ? Locale.SIMPLIFIED_CHINESE : locale;
        } catch (Exception e) {
            return Locale.SIMPLIFIED_CHINESE;
        }
    }

    private static Long currentTenantId() {
        Long tenantId = TenantContext.tenantId();
        return tenantId != null ? tenantId : 1L;
    }
}
