package com.dataweave.api;

import java.util.List;

import com.dataweave.master.domain.lineage.LineageHintRepository;
import com.dataweave.master.domain.lineage.LineageUnresolvedHint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 041 T012：hint 仓储 replace-per-task 语义 + 按任务查询（H2 兼容）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class LineageHintRepositoryTest {

    @Autowired
    LineageHintRepository repo;

    @Test
    void replacePerTaskSemantics() {
        repo.save(new LineageUnresolvedHint(1L, 1L, 901L, 1, "DYNAMIC_TABLE", "L4: df.to_sql(tbl)"));
        repo.save(new LineageUnresolvedHint(1L, 1L, 901L, 1, "DYNAMIC_SQL", "L9: f-string sql"));
        repo.save(new LineageUnresolvedHint(1L, 1L, 902L, 1, "TIMEOUT", "budget"));

        assertThat(repo.findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(1L, 1L, 901L)).hasSize(2);

        // replace：删 901 旧提示后重插 1 条，902 不受影响
        repo.deleteForTask(1L, 1L, 901L);
        repo.save(new LineageUnresolvedHint(1L, 1L, 901L, 2, "PARSE_FAIL", "L1: broken"));

        List<LineageUnresolvedHint> after = repo.findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(1L, 1L, 901L);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getKind()).isEqualTo("PARSE_FAIL");
        assertThat(after.get(0).getVersionNo()).isEqualTo(2);
        assertThat(repo.findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(1L, 1L, 902L)).hasSize(1);
    }

    @Test
    void isolationByTenantAndProject() {
        repo.save(new LineageUnresolvedHint(2L, 5L, 903L, null, "DYNAMIC_TABLE", "L1: x"));
        assertThat(repo.findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(1L, 1L, 903L)).isEmpty();
        assertThat(repo.findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(2L, 5L, 903L)).hasSize(1);
    }
}
