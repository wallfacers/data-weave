package com.dataweave.master.application;

/**
 * 节点受控执行网关。master 侧定义契约，实现在 api 模块（WebClient 转发到 worker exec 端点）。
 *
 * <p>依赖方向：master 不依赖 api，故用接口 + ObjectProvider 在运行时拿实现（section 3 接线）。
 */
public interface NodeExecGateway {

    /**
     * 在指定节点执行白名单命令。
     *
     * @param nodeCode 目标节点
     * @param command  命令串（已过 PolicyEngine 裁决）
     * @return 执行结果
     */
    ExecResult exec(String nodeCode, String command);

    /**
     * 节点执行结果。
     *
     * @param success   是否成功（节点在线、命令被接受并执行）
     * @param exitCode  退出码（超时/拒绝为 null）
     * @param stdout    标准输出（可能截断）
     * @param stderr    标准错误
     * @param truncated 输出是否被截断
     * @param message   面向用户/审计的摘要
     */
    record ExecResult(boolean success, Integer exitCode, String stdout, String stderr,
                      boolean truncated, String message) {
    }
}
