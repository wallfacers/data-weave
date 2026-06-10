package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface TenantRepository extends CrudRepository<Tenant, Long> {

    Optional<Tenant> findByCode(String code);
}
