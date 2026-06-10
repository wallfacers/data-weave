package com.dataweave.master.application;

/**
 * 副作用操作分级。L0 只读 → L4 禁止；ordinal 即危险度，可比较取最大。
 *
 * <ul>
 *   <li>L0 只读 → 直接执行 + 审计</li>
 *   <li>L1 可逆例行写 → 直接执行 + 审计 + 右舷通知</li>
 *   <li>L2 高影响写 → 审批单</li>
 *   <li>L3 不可逆写 → 审批单 + 二次确认（回输对象名）</li>
 *   <li>L4 禁止 → 永久拒绝</li>
 * </ul>
 */
public enum PolicyLevel {
    L0, L1, L2, L3, L4;

    public static PolicyLevel parse(String s) {
        if (s == null) {
            return L2; // 未知按宁严
        }
        try {
            return PolicyLevel.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return L2;
        }
    }

    public PolicyLevel max(PolicyLevel other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }

    public boolean atLeast(PolicyLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
