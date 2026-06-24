package com.dataweave.master.infrastructure;

import com.dataweave.master.domain.BackfillRun;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.Uuid7;
import com.dataweave.master.domain.WorkflowInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;

/**
 * 实例类（UUIDv7 客户端赋值主键）的 Spring Data JDBC 持久化回调。
 *
 * <p>{@link TaskInstance}/{@link WorkflowInstance} 主键不在 {@code new} 时赋值（保持 null），
 * 由本回调在「转换为行之前」按需生成 {@code Uuid7.generate()}。如此 Spring Data JDBC 的默认
 * {@code isNew}（基于 id 是否为 null）策略得以保留：null id → INSERT 并在此刻赋时间有序主键；
 * 非 null id（从库加载的实体）→ UPDATE。无需 {@code Persistable}，也不向 JSON 泄漏 isNew 属性。
 */
@Configuration
public class InstancePersistenceConfig {

    @Bean
    BeforeConvertCallback<TaskInstance> taskInstanceIdAssign() {
        return (instance) -> {
            if (instance.getId() == null) {
                instance.setId(Uuid7.generate());
            }
            return instance;
        };
    }

    @Bean
    BeforeConvertCallback<WorkflowInstance> workflowInstanceIdAssign() {
        return (instance) -> {
            if (instance.getId() == null) {
                instance.setId(Uuid7.generate());
            }
            return instance;
        };
    }

    @Bean
    BeforeConvertCallback<BackfillRun> backfillRunIdAssign() {
        return (run) -> {
            if (run.getId() == null) {
                run.setId(Uuid7.generate());
            }
            return run;
        };
    }
}
