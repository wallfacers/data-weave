package com.dataweave.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * DataWeave 后端启动入口（dataweave-api）。
 *
 * <p>扫描整个 com.dataweave 包以装配 master/worker/alert 模块的组件；
 * 显式开启 Spring Data JDBC repositories（master 模块的仓储在 com.dataweave.master.domain）。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.dataweave")
@EnableJdbcRepositories(basePackages = "com.dataweave.master.domain")
public class DataWeaveApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataWeaveApiApplication.class, args);
    }
}
