package com.dataweave.master.application;

import com.dataweave.master.domain.Finding;

import java.util.List;

/**
 * 主动发现的可插拔巡检器 SPI。
 *
 * <p>任何模块（任务失败 / 数据质量 / SLA / 血缘断裂…）实现本接口并注册为 Spring Bean 即接入主动发现链路，
 * **不修改任何下游**：{@link InspectorScheduler} 自动纳入其巡检，产出的 {@link Finding} 经 {@link FindingService}
 * 统一去重落库，再统一上举手台 / 主动播报 / 闸门修复。
 *
 * <p>实现约定：{@link #inspect()} 只负责"扫描自己负责的域、产出候选 Finding"，去重与持久化交给调度器+FindingService，
 * 实现方无需自行查重或落库。
 */
public interface Inspector {

    /** 本巡检器产出的 Finding 来源标识（与 {@link Finding#getSource()} 一致，如 {@code "TASK_FAILURE"}）。 */
    String source();

    /** 扫描负责的域，返回本轮发现的候选 Finding（可能与已落库的重复，去重由上层负责）。 */
    List<Finding> inspect();
}
