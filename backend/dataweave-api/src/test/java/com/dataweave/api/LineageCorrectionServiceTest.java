package com.dataweave.api;

import com.dataweave.master.application.lineage.LineageCorrectionService;
import com.dataweave.master.domain.lineage.LineageStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 041 T017：修正服务——语义键 UPSERT 幂等 / REVOKE 删行 / 抑制键集供给（h2 真库，mock 图存储）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class LineageCorrectionServiceTest {

    @Autowired
    LineageCorrectionService service;

    @MockitoBean
    LineageStore lineageStore;

    private static final String TK = "1|datasource:|dw.orders";

    @Test
    void upsertIsIdempotentPerSemanticKey() {
        var v1 = service.apply(1, 1, 501, "REMOVE", "WRITE", TK, null, "admin");
        var v2 = service.apply(1, 1, 501, "REMOVE", "WRITE", TK, null, "admin");
        assertThat(v1.id()).isEqualTo(v2.id());
        assertThat(v2.status()).isEqualTo("REMOVED");

        // 同键改判 CONFIRM → 覆盖同一行
        var v3 = service.apply(1, 1, 501, "CONFIRM", "WRITE", TK, null, "admin2");
        assertThat(v3.id()).isEqualTo(v1.id());
        assertThat(v3.status()).isEqualTo("CONFIRMED");
        assertThat(v3.operator()).isEqualTo("admin2");
        assertThat(service.listForTask(1, 1, 501)).hasSize(1);
    }

    @Test
    void revokeDeletesDecision() {
        service.apply(1, 1, 502, "REMOVE", "READ", TK, null, "admin");
        assertThat(service.listForTask(1, 1, 502)).hasSize(1);
        var revoked = service.apply(1, 1, 502, "REVOKE", "READ", TK, null, "admin");
        assertThat(revoked).isNull();
        assertThat(service.listForTask(1, 1, 502)).isEmpty();
        // 再撤销一次：幂等无异常
        service.apply(1, 1, 502, "REVOKE", "READ", TK, null, "admin");
    }

    @Test
    void gateDecisionsExposeSemanticKeys() {
        service.apply(1, 1, 503, "REMOVE", "WRITE", TK, null, "admin");
        service.apply(1, 1, 503, "CONFIRM", "READ", TK, "amount", "admin");
        var decisions = service.decisionsFor(1, 1, 503);
        assertThat(decisions).containsEntry("WRITE|" + TK, "REMOVED");
        assertThat(decisions).containsEntry("READ|" + TK + "|amount", "CONFIRMED");
    }

    @Test
    void columnLevelKeyIsIndependentFromTableLevel() {
        service.apply(1, 1, 504, "REMOVE", "WRITE", TK, "col_a", "admin");
        var decisions = service.decisionsFor(1, 1, 504);
        assertThat(decisions).containsKey("WRITE|" + TK + "|col_a");
        assertThat(decisions).doesNotContainKey("WRITE|" + TK);
    }
}
