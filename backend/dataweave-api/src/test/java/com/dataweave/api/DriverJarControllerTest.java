package com.dataweave.api;

import com.dataweave.api.infrastructure.JwtUtil;
import com.dataweave.master.application.DatasourceDtos.DriverJarVO;
import com.dataweave.master.application.DriverJarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * DriverJarController HTTP 层端到端（datasource-driver-isolation）：list / delete。
 *
 * <p>上传的 multipart 解析逻辑由 {@code DriverJarServiceTest} 覆盖；此处用 {@link DriverJarService}
 * 直接灌入资产后，经 {@code WebTestClient} 验证 HTTP GET/DELETE 链路（鉴权 + 序列化 + 软删除）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class DriverJarControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;
    @Autowired
    DriverJarService driverJarService;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", JwtTestSupport.bearer(jwtUtil))
                .build();
    }

    @Test
    void list_returnsUploadedAsset() {
        driverJarService.upload("MYSQL", "mysql.jar", jarWithDriver("com.fake.Driver"), 1L);

        client.get().uri("/api/driver-jars?typeCode=MYSQL")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data[0].driverClass").isEqualTo("com.fake.Driver")
                .jsonPath("$.data[0].status").isEqualTo("ACTIVE");
    }

    @Test
    void delete_removesAssetWhenNotReferenced() {
        DriverJarVO vo = driverJarService.upload("POSTGRES", "pg.jar", jarWithDriver("org.fake.Pg"), 1L);

        client.delete().uri("/api/driver-jars/" + vo.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }

    private static byte[] jarWithDriver(String driverClass) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             JarOutputStream jos = new JarOutputStream(baos)) {
            JarEntry e = new JarEntry("META-INF/services/java.sql.Driver");
            jos.putNextEntry(e);
            jos.write(driverClass.getBytes());
            jos.closeEntry();
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
