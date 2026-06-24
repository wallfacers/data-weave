package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

public interface BackfillRunRepository extends CrudRepository<BackfillRun, UUID> {
}
