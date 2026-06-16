package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;

public interface TagRepository extends CrudRepository<Tag, Long> {
    List<Tag> findByProjectIdOrderByNameAsc(Long projectId);

    Optional<Tag> findByProjectIdAndName(Long projectId, String name);
}
