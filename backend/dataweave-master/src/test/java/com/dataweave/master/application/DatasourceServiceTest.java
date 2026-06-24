package com.dataweave.master.application;

import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.DatasourceType;
import com.dataweave.master.domain.DatasourceTypeRepository;
import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.dataweave.master.application.DatasourceDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatasourceServiceTest {

    @Mock DatasourceRepository datasourceRepository;
    @Mock DatasourceTypeRepository datasourceTypeRepository;
    @Mock TaskDefRepository taskDefRepository;
    @Mock DriverJarRepository driverJarRepository;
    @Mock DatasourceEncryptor encryptor;

    DatasourceService service;

    @BeforeEach
    void setUp() {
        service = new DatasourceService(datasourceRepository, datasourceTypeRepository, taskDefRepository, driverJarRepository, encryptor);
    }

    @Test
    void listByProject_returnsMaskedPasswords() {
        Datasource ds = sampleDatasource(1L, "orders_mysql", "MYSQL");
        when(datasourceRepository.findByTenantIdAndProjectIdAndDeleted(1L, 1L, 0))
                .thenReturn(List.of(ds));

        List<DatasourceVO> result = service.listByProject(1L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).passwordEnc()).isEqualTo("******");
        assertThat(result.get(0).name()).isEqualTo("orders_mysql");
    }

    @Test
    void getById_success_returnsMaskedPassword() {
        Datasource ds = sampleDatasource(1L, "orders_mysql", "MYSQL");
        when(datasourceRepository.findById(1L)).thenReturn(Optional.of(ds));

        DatasourceVO vo = service.getById(1L);

        assertThat(vo.name()).isEqualTo("orders_mysql");
        assertThat(vo.passwordEnc()).isEqualTo("******");
    }

    @Test
    void getById_notFound_throws404() {
        when(datasourceRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(999L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("datasource.not_found");
    }

    @Test
    void create_success_encryptsPassword() {
        when(datasourceRepository.existsByProjectIdAndNameAndDeleted(1L, "new_ds", 0))
                .thenReturn(false);
        when(encryptor.encrypt("***")).thenReturn("encrypted_pw");
        when(datasourceRepository.save(any(Datasource.class))).thenAnswer(inv -> {
            Datasource d = inv.getArgument(0);
            d.setId(10L);
            return d;
        });

        DatasourceCreateRequest req = new DatasourceCreateRequest(
                "new_ds", "MYSQL", 1L, "10.0.0.1", 3306, "mydb",
                null, "admin", "***", null, "test desc", null);

        DatasourceVO vo = service.create(req, 1L);

        assertThat(vo.id()).isEqualTo(10L);
        assertThat(vo.passwordEnc()).isEqualTo("******");

        // Verify password was encrypted before save
        ArgumentCaptor<Datasource> captor = ArgumentCaptor.forClass(Datasource.class);
        verify(datasourceRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordEnc()).isEqualTo("encrypted_pw");
    }

    @Test
    void create_duplicateName_throws409() {
        when(datasourceRepository.existsByProjectIdAndNameAndDeleted(1L, "dup_ds", 0))
                .thenReturn(true);

        DatasourceCreateRequest req = new DatasourceCreateRequest(
                "dup_ds", "MYSQL", 1L, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(req, 1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getHttpStatus()).isEqualTo(409));
    }

    @Test
    void update_changePassword_reEncrypts() {
        Datasource ds = sampleDatasource(1L, "orders_mysql", "MYSQL");
        ds.setPasswordEnc("old_encrypted");
        when(datasourceRepository.findById(1L)).thenReturn(Optional.of(ds));
        when(encryptor.encrypt("new_pw")).thenReturn("new_encrypted");
        when(datasourceRepository.save(any(Datasource.class))).thenReturn(ds);

        DatasourceUpdateRequest req = new DatasourceUpdateRequest(
                null, null, null, null, null, null, null, "new_pw", null, null, null, null);

        service.update(1L, req);

        ArgumentCaptor<Datasource> captor = ArgumentCaptor.forClass(Datasource.class);
        verify(datasourceRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordEnc()).isEqualTo("new_encrypted");
    }

    @Test
    void update_emptyPassword_preservesExisting() {
        Datasource ds = sampleDatasource(1L, "orders_mysql", "MYSQL");
        ds.setPasswordEnc("existing_encrypted");
        when(datasourceRepository.findById(1L)).thenReturn(Optional.of(ds));
        when(datasourceRepository.save(any(Datasource.class))).thenReturn(ds);

        DatasourceUpdateRequest req = new DatasourceUpdateRequest(
                null, null, "10.0.0.2", null, null, null, null, null, null, null, null, null);

        service.update(1L, req);

        // Password should NOT be re-encrypted (stays as-is)
        ArgumentCaptor<Datasource> captor = ArgumentCaptor.forClass(Datasource.class);
        verify(datasourceRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordEnc()).isEqualTo("existing_encrypted");
        verify(encryptor, never()).encrypt(anyString());
    }

    @Test
    void delete_softDeletesAndReturnsReferenceCount() {
        Datasource ds = sampleDatasource(1L, "orders_mysql", "MYSQL");
        when(datasourceRepository.findById(1L)).thenReturn(Optional.of(ds));
        when(taskDefRepository.countByDatasourceIdAndDeleted(1L, 0)).thenReturn(3L);
        when(taskDefRepository.countByTargetDatasourceIdAndDeleted(1L, 0)).thenReturn(1L);

        DeleteResult result = service.delete(1L);

        assertThat(result.deleted()).isTrue();
        assertThat(result.referencedTaskCount()).isEqualTo(4L);
        assertThat(result.warning()).contains("4");

        // Verify soft delete
        ArgumentCaptor<Datasource> captor = ArgumentCaptor.forClass(Datasource.class);
        verify(datasourceRepository).save(captor.capture());
        assertThat(captor.getValue().getDeleted()).isEqualTo(1);
    }

    @Test
    void delete_notFound_throws404() {
        when(datasourceRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(999L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo("datasource.not_found");
    }

    @Test
    void create_withDriverJarId_active_setsIt() {
        DriverJar jar = new DriverJar();
        jar.setId(5L);
        jar.setStatus("ACTIVE");
        jar.setDeleted(0);
        when(driverJarRepository.findById(5L)).thenReturn(Optional.of(jar));
        when(datasourceRepository.existsByProjectIdAndNameAndDeleted(1L, "ds_jar", 0)).thenReturn(false);
        // port is null → default port fallback
        DatasourceType mysqlType = new DatasourceType();
        mysqlType.setDefaultPort(3306);
        when(datasourceTypeRepository.findByCode("MYSQL")).thenReturn(Optional.of(mysqlType));
        when(datasourceRepository.save(any(Datasource.class))).thenAnswer(inv -> {
            Datasource d = inv.getArgument(0);
            d.setId(20L);
            return d;
        });

        DatasourceCreateRequest req = new DatasourceCreateRequest(
                "ds_jar", "MYSQL", 1L, null, null, null, null, null, null, null, null, 5L);
        DatasourceVO vo = service.create(req, 1L);

        assertThat(vo.driverJarId()).isEqualTo(5L);
        assertThat(vo.driverSource()).isEqualTo("uploaded");
    }

    @Test
    void create_withDriverJarId_pending_rejected409() {
        DriverJar jar = new DriverJar();
        jar.setId(5L);
        jar.setStatus("PENDING");
        jar.setDeleted(0);
        when(datasourceRepository.existsByProjectIdAndNameAndDeleted(1L, "ds_jar", 0)).thenReturn(false);
        when(driverJarRepository.findById(5L)).thenReturn(Optional.of(jar));
        // port is null → default port fallback
        DatasourceType mysqlType = new DatasourceType();
        mysqlType.setDefaultPort(3306);
        when(datasourceTypeRepository.findByCode("MYSQL")).thenReturn(Optional.of(mysqlType));

        DatasourceCreateRequest req = new DatasourceCreateRequest(
                "ds_jar", "MYSQL", 1L, null, null, null, null, null, null, null, null, 5L);
        assertThatThrownBy(() -> service.create(req, 1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getHttpStatus()).isEqualTo(409));
    }

    private static Datasource sampleDatasource(Long id, String name, String typeCode) {
        Datasource ds = new Datasource();
        ds.setId(id);
        ds.setTenantId(1L);
        ds.setProjectId(1L);
        ds.setName(name);
        ds.setTypeCode(typeCode);
        ds.setHost("10.0.0.20");
        ds.setPort(3306);
        ds.setDatabaseName("shop");
        ds.setJdbcUrl("jdbc:mysql://10.0.0.20:3306/shop");
        ds.setUsername("app");
        ds.setPasswordEnc("encrypted_pw");
        ds.setStatus("ACTIVE");
        ds.setDeleted(0);
        return ds;
    }
}
