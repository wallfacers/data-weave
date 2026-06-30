package com.dataweave.master.domain.asset;

/**
 * 资产生命周期（data_model 状态机）：
 * ACTIVE ──底层表删/改名(schema 对账不一致)──► STALE ──确认下线──► RETIRED；STALE ──恢复──► ACTIVE。
 * 不变量：STALE 时 UI 不展示「可信绿灯」。
 */
public enum AssetStatus {
    ACTIVE,
    STALE,
    RETIRED
}
