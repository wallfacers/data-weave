package com.dataweave.master.domain;

/**
 * 运行环境常量（workflow_instance / task_instance 的 env 取值），避免散落字面量。
 *
 * <p>当前阶段为<strong>逻辑隔离</strong>语义标签：{@link #PROD} = cron 周期 / 正式手动运行（跑规定性快照）；
 * {@link #DEV} = 画布试跑（跑草稿）。与 {@code run_mode}（NORMAL/TEST）正交——run_mode 表达「计不计统计/
 * 跑草稿与否」，env 表达「运行环境」。env 当前不驱动 datasource 选择或调度分区（物理隔离留后续 change）。
 */
public final class Envs {

    public static final String PROD = "PROD";
    public static final String DEV = "DEV";

    private Envs() {
    }
}
