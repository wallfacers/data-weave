package com.dataweave.master.lineage;

import com.dataweave.master.infrastructure.lineage.Neo4jLineageStore;
import com.dataweave.master.infrastructure.lineage.Neo4jSchemaInitializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.neo4j.Neo4jContainer;

/**
 * 血缘 neo4j 集成测试公共底座（T010）。
 *
 * <p>每个 IT 子类启动自己的 {@link Neo4jContainer}（真容器，沿用后端测试隔离不变量：独立库实例）。
 * 用 JUnit 6 原生 {@code @BeforeAll/@AfterAll} 手动管理生命周期，避免依赖 testcontainers 2.x 的
 * junit-jupiter 集成注解包路径；只依赖稳定的 {@code Neo4jContainer} 核心类。
 *
 * <ul>
 *   <li>{@link #newStore()} —— 构造直连容器的新 {@link Neo4jLineageStore}（含约束初始化），供轻量 IT 直接测写入。</li>
 *   <li>{@link #cleanDb(Driver)} —— 每测前清库（{@code MATCH (n) DETACH DELETE n}）。</li>
 *   <li>{@link #neo4jProps(DynamicPropertyRegistry)} —— {@code @DynamicPropertySource}，供重型 {@code @SpringBootTest}
 *       子类（PushLineageIT）把 {@code lineage.neo4j.*} 指向容器。</li>
 * </ul>
 */
public abstract class Neo4jTestSupport {

    /** 外部 neo4j 模式：设 NEO4J_TEST_URI（如 bolt://localhost:7687）则直连，免 testcontainers/Docker daemon。
     *  无 Docker Desktop WSL 集成时，可用手工启动的 neo4j 容器跑 IT。可选 NEO4J_TEST_PASSWORD 覆盖密码（默认 dataweave）。 */
    static final String EXTERNAL_URI = System.getenv("NEO4J_TEST_URI");

    static Neo4jContainer neo4j;
    static String boltUri;
    static String neo4jPassword = "dataweave";

    @BeforeAll
    static void startNeo4j() {
        if (EXTERNAL_URI != null && !EXTERNAL_URI.isBlank()) {
            // 外部直连模式：不启 testcontainers 容器
            boltUri = EXTERNAL_URI;
            String p = System.getenv("NEO4J_TEST_PASSWORD");
            if (p != null && !p.isBlank()) {
                neo4jPassword = p;
            }
            return;
        }
        neo4j = new Neo4jContainer("neo4j:5");
        neo4j.start();
        boltUri = neo4j.getBoltUrl();
        neo4jPassword = neo4j.getAdminPassword();
    }

    @AfterAll
    static void stopNeo4j() {
        if (neo4j != null) {
            neo4j.stop();
        }
    }

    /** 供重型 @SpringBootTest 子类：把 lineage.neo4j.{uri,username,password} 指向容器（或外部 neo4j）。 */
    @DynamicPropertySource
    static void neo4jProps(DynamicPropertyRegistry registry) {
        registry.add("lineage.neo4j.uri", () -> boltUri);
        registry.add("lineage.neo4j.username", () -> "neo4j");
        registry.add("lineage.neo4j.password", () -> neo4jPassword);
    }

    /** 直连容器/外部 neo4j 的新 Driver（短超时，韧性配置与生产 Neo4jConfig 一致）。 */
    protected Driver newDriver() {
        return GraphDatabase.driver(boltUri, AuthTokens.basic("neo4j", neo4jPassword));
    }

    /** 构造直连容器的新 store：建约束 + 清库，返回全新 store 实例（轻量 IT 用）。 */
    protected Neo4jLineageStore newStore() {
        Driver driver = newDriver();
        new Neo4jSchemaInitializer(driver).initialize();
        return new Neo4jLineageStore(driver);
    }

    /** 每测前清空全库（隔离）。 */
    protected void cleanDb(Driver driver) {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }
}
