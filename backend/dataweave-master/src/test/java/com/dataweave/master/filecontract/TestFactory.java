package com.dataweave.master.filecontract;

import com.dataweave.master.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared factory for building test domain objects matching the analytics example
 * from quickstart.md.
 */
final class TestFactory {

    private TestFactory() {}

    /** Full analytics project with tasks, workflows, catalog, and tags. */
    static ProjectExport analyticsExport() {
        var project = new Project();
        project.setId(1L);
        project.setCode("analytics");
        project.setName("数据分析项目");

        // Catalog tree: root + orders + staging
        var root = new CatalogNode();
        root.setId(10L);
        root.setName("分析项目根");
        root.setSortOrder(0);
        root.setPath("");
        root.setParentId(null);

        var orders = new CatalogNode();
        orders.setId(11L);
        orders.setName("订单域");
        orders.setSortOrder(10);
        orders.setPath("orders");
        orders.setParentId(10L);

        var staging = new CatalogNode();
        staging.setId(12L);
        staging.setName("暂存区");
        staging.setSortOrder(20);
        staging.setPath("staging");
        staging.setParentId(10L);

        // Tags
        var critical = new Tag();
        critical.setId(100L);
        critical.setName("critical");
        critical.setColor("#ff3b30");

        var nightly = new Tag();
        nightly.setId(101L);
        nightly.setName("nightly");
        nightly.setColor("#3366cc");

        // Tasks
        var etlTask = new TaskDef();
        etlTask.setId(200L);
        etlTask.setName("订单 ETL");
        etlTask.setType("SQL");
        etlTask.setDescription("每日订单宽表抽取");
        etlTask.setPriority(5);
        etlTask.setTimeoutSec(600);
        etlTask.setRetryMax(2);
        etlTask.setFrozen(0);
        etlTask.setContent("INSERT INTO mart_orders.daily\n" +
                "SELECT order_date, SUM(amount)\n" +
                "FROM warehouse_main.orders\n" +
                "WHERE order_date = '${bizDate}'\n" +
                "GROUP BY order_date;\n");
        etlTask.setParamsJson("{\"bizDate\":\"${yyyyMMdd}\",\"mode\":\"full\"}");
        etlTask.setCatalogNodeId(11L);
        // datasource codes set in C layer, null here

        var notifyTask = new TaskDef();
        notifyTask.setId(201L);
        notifyTask.setName("订单完成通知");
        notifyTask.setType("SHELL");
        notifyTask.setDescription("发送钉钉通知");
        notifyTask.setPriority(8);
        notifyTask.setTimeoutSec(60);
        notifyTask.setRetryMax(0);
        notifyTask.setFrozen(0);
        notifyTask.setContent("#!/bin/bash\n" +
                "echo \"Orders pipeline completed at $(date)\" | curl -X POST -d @- " +
                "https://hooks.example.com/dingtalk\n");
        notifyTask.setParamsJson("{\"env\":\"prod\"}");
        notifyTask.setCatalogNodeId(11L);

        // Workflow
        var wf = new WorkflowDef();
        wf.setId(300L);
        wf.setName("每日订单流");
        wf.setScheduleType("CRON");
        wf.setCron("0 0 2 * * ?");
        wf.setPriority(5);
        wf.setPreemptible(1);
        wf.setTimeoutSec(3600);
        wf.setCatalogNodeId(11L);

        // Workflow nodes
        var nElt = new WorkflowNode();
        nElt.setId(400L);
        nElt.setWorkflowId(300L);
        nElt.setNodeKey("n_etl");
        nElt.setNodeType("TASK");
        nElt.setName("抽取");
        nElt.setPosX(200);
        nElt.setPosY(120);
        nElt.setTaskId(200L);

        var nNotify = new WorkflowNode();
        nNotify.setId(401L);
        nNotify.setWorkflowId(300L);
        nNotify.setNodeKey("n_notify");
        nNotify.setNodeType("TASK");
        nNotify.setName("通知");
        nNotify.setPosX(400);
        nNotify.setPosY(120);
        nNotify.setTaskId(201L);

        var nStart = new WorkflowNode();
        nStart.setId(402L);
        nStart.setWorkflowId(300L);
        nStart.setNodeKey("start");
        nStart.setNodeType("VIRTUAL");
        nStart.setName("起点");
        nStart.setPosX(60);
        nStart.setPosY(120);
        nStart.setTaskId(null);

        // Workflow edges
        var e1 = new WorkflowEdge();
        e1.setId(500L);
        e1.setWorkflowId(300L);
        e1.setFromNodeId(400L);  // n_etl
        e1.setToNodeId(401L);    // n_notify
        e1.setStrength("STRONG");

        var e2 = new WorkflowEdge();
        e2.setId(501L);
        e2.setWorkflowId(300L);
        e2.setFromNodeId(402L);  // start
        e2.setToNodeId(400L);    // n_etl
        e2.setStrength("STRONG");

        // Entity tags
        var et1 = new EntityTag();
        et1.setTagId(100L);  // critical
        et1.setEntityType(EntityTag.TYPE_TASK);
        et1.setEntityId(200L);  // etl task

        var et2 = new EntityTag();
        et2.setTagId(101L);  // nightly
        et2.setEntityType(EntityTag.TYPE_TASK);
        et2.setEntityId(200L);  // etl task

        var et3 = new EntityTag();
        et3.setTagId(100L);  // critical
        et3.setEntityType(EntityTag.TYPE_TASK);
        et3.setEntityId(201L);  // notify task

        var et4 = new EntityTag();
        et4.setTagId(101L);  // nightly
        et4.setEntityType(EntityTag.TYPE_WORKFLOW);
        et4.setEntityId(300L);  // workflow

        return new ProjectExport(
                project,
                List.of(root, orders, staging),
                List.of(critical, nightly),
                List.of(et1, et2, et3, et4),
                List.of(etlTask, notifyTask),
                List.of(wf),
                List.of(nElt, nNotify, nStart),
                List.of(e1, e2),
                Map.of(200L, "orders_etl", 201L, "notify"),
                Map.of(300L, "daily_orders"),
                // datasource logical codes (FR-009): carried beside the Long datasourceId
                Map.of(200L, "warehouse_main"),
                Map.of(200L, "mart_orders")
        );
    }

    /** Minimal project with one task, no tags, single catalog. */
    static ProjectExport minimalExport() {
        var project = new Project();
        project.setId(1L);
        project.setCode("minimal");
        project.setName("最小项目");

        var root = new CatalogNode();
        root.setId(1L);
        root.setName("root");
        root.setPath("");

        var task = new TaskDef();
        task.setId(1L);
        task.setName("简单任务");
        task.setType("ECHO");
        task.setContent("echo hello");
        task.setCatalogNodeId(1L);

        return new ProjectExport(
                project, List.of(root), List.of(), List.of(),
                List.of(task), List.of(), List.of(), List.of(),
                Map.of(1L, "simple_task"), Map.of()
        );
    }

    /**
     * Convert ProjectImport back to ProjectExport for round-trip testing.
     * Deserialization now preserves slug/path identity + datasource codes, so this
     * simply delegates to {@link ProjectImport#toExport()} (no fabricated slugs).
     */
    static ProjectExport toExport(ProjectImport imported) {
        return imported.toExport();
    }
}
