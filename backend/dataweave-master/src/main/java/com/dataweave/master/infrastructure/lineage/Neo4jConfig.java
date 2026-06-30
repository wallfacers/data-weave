package com.dataweave.master.infrastructure.lineage;

import java.util.concurrent.TimeUnit;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j {@link Driver} 自建 @Bean（lineage-neo4j-store）。
 *
 * <p>Spring Boot 4 无 neo4j driver 自动配置（与 {@code WebClient.Builder} 同类坑，CLAUDE.md 明列 SB4 须自建 @Bean），
 * 不引入 Spring Data Neo4j。对标 {@code dataweave-api/.../infrastructure/WebClientConfig.java} 的自建 Bean 模式。
 *
 * <p>韧性（FR-007 / SC-004）：driver 创建懒连接，不要求 neo4j 在线；连接/获取超时设短（5s），
 * 使 neo4j 不可达时 SchemaInitializer / Seeder / recordTaskIo 快速失败、降级记日志，不拖垮建任务 / push 主链路。
 * 配置键 {@code lineage.neo4j.{uri,username,password}}（默认开发态 bolt://localhost:7687 / neo4j / dataweave）。
 */
@Configuration
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(
            @Value("${lineage.neo4j.uri:bolt://localhost:7687}") String uri,
            @Value("${lineage.neo4j.username:neo4j}") String username,
            @Value("${lineage.neo4j.password:dataweave}") String password) {
        Config config = Config.builder()
                .withConnectionTimeout(5, TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(5, TimeUnit.SECONDS)
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }
}
