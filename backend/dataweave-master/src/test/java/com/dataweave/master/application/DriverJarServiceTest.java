package com.dataweave.master.application;

import com.dataweave.master.application.DatasourceDtos.DriverJarVO;
import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.infrastructure.DriverJarStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DriverJarService 单测（datasource-driver-isolation）：上传校验 / sha256 去重 / 引用中删除 409。
 */
@ExtendWith(MockitoExtension.class)
class DriverJarServiceTest {

    @Mock DriverJarRepository driverJarRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock DriverJarStorage storage;

    DriverJarService service;

    @BeforeEach
    void setUp() {
        service = new DriverJarService(driverJarRepository, datasourceRepository, storage);
        lenient().when(storage.type()).thenReturn("LOCAL");
    }

    @Test
    void upload_notJar_rejected() {
        assertThatThrownBy(() -> service.upload("MYSQL", "foo.zip", new byte[]{1}, 1L))
                .isInstanceOf(BizException.class);
    }

    @Test
    void upload_noJdbcImpl_rejected() throws Exception {
        byte[] jar = jarWithoutDriverService();
        assertThatThrownBy(() -> service.upload("MYSQL", "empty.jar", jar, 1L))
                .isInstanceOf(BizException.class);
    }

    @Test
    void upload_validJar_storedActive() throws Exception {
        byte[] jar = jarWithDriverService("com.fake.Driver");
        when(driverJarRepository.findByTenantIdAndSha256AndDeleted(anyLong(), anyString(), eq(0)))
                .thenReturn(Optional.empty());
        when(driverJarRepository.save(any(DriverJar.class))).thenAnswer(inv -> {
            DriverJar j = inv.getArgument(0);
            j.setId(1L);
            return j;
        });

        DriverJarVO vo = service.upload("MYSQL", "mysql.jar", jar, 1L);

        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.driverClass()).isEqualTo("com.fake.Driver");
        verify(storage).put(anyString(), eq(jar));
    }

    @Test
    void delete_inUse_throws409() {
        DriverJar jar = new DriverJar();
        jar.setId(1L);
        jar.setStorageKey("k");
        jar.setDeleted(0);
        when(driverJarRepository.findById(1L)).thenReturn(Optional.of(jar));
        when(datasourceRepository.countByDriverJarIdAndDeleted(1L, 0)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getHttpStatus()).isEqualTo(409));
    }

    private static byte[] jarWithDriverService(String driverClass) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            JarEntry e = new JarEntry("META-INF/services/java.sql.Driver");
            jos.putNextEntry(e);
            jos.write(driverClass.getBytes());
            jos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] jarWithoutDriverService() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            jos.putNextEntry(new JarEntry("foo.txt"));
            jos.write("bar".getBytes());
            jos.closeEntry();
        }
        return baos.toByteArray();
    }
}
