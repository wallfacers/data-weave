package com.dataweave.master.lineage;

import com.dataweave.master.infrastructure.lineage.Neo4jLineageSeeder;
import com.dataweave.master.infrastructure.lineage.Neo4jLineageStore;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 018 T032：{@link Neo4jLineageSeeder} 幂等性验证。重复 seed() 两次，断言节点/边不翻倍
 * （replace-per-task + MERGE 保证）。真 neo4j 容器。
 */
class Neo4jLineageSeederIT extends Neo4jTestSupport {

    @Test
    void seed_twice_noDuplication() {
        Driver driver = newDriver();
        cleanDb(driver);
        Neo4jLineageStore store = newStore();
        Neo4jLineageSeeder seeder = new Neo4jLineageSeeder(store);

        seeder.seed();
        seeder.seed(); // 重复播种

        // 节点：5 表 + orders(metric 下游) = 6 :Table；3 :Task；1 :Metric；1 :Datasource
        assertThat(count(driver, "MATCH (t:Table) RETURN count(t)")).isEqualTo(6L);
        assertThat(count(driver, "MATCH (t:Task) RETURN count(t)")).isEqualTo(3L);
        assertThat(count(driver, "MATCH (m:Metric) RETURN count(m)")).isEqualTo(1L);
        assertThat(count(driver, "MATCH (d:Datasource) RETURN count(d)")).isEqualTo(1L);

        // 边：7 READS/WRITES + 4 派生 FLOWS_TO + 1 COMPUTED_FROM
        assertThat(count(driver, "MATCH (:Task)-[r:READS|WRITES]->() RETURN count(r)")).isEqualTo(7L);
        assertThat(count(driver, "MATCH ()-[f:FLOWS_TO]->() RETURN count(f)")).isEqualTo(4L);
        assertThat(count(driver, "MATCH (:Metric)-[:COMPUTED_FROM]->() RETURN count(*)")).isEqualTo(1L);
    }

    private static long count(Driver driver, String cypher) {
        try (Session session = driver.session()) {
            return session.run(cypher).single().get(0).asLong();
        }
    }
}
