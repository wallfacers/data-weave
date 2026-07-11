package com.dataweave.master.application;

/**
 * 062 Flink savepoint 触发客户端（US3 优雅停止保留进度）。
 *
 * <p>抽为接口 → 生产用 {@code HttpFlinkSavepointClient}（Flink REST），测试注入 fake，避免真 HTTP。
 * 语义：对一个运行中的 Flink 作业触发 <b>stop-with-savepoint</b>（Flink REST
 * {@code POST /jobs/{jobId}/stop} → 轮询 {@code GET /jobs/{jobId}/savepoints/{triggerId}} 至完成），
 * 返回 savepoint 路径。失败/超时/引擎不可达 → 抛 {@link SavepointException}（映射 streaming.savepoint.unavailable）。
 */
public interface FlinkSavepointClient {

    /**
     * 触发 stop-with-savepoint 并等待完成。
     *
     * @param restEndpoint    Flink REST 端点（来自 external_job_handle）
     * @param jobId           作业 ID
     * @param targetDirectory savepoint 目录（null=用引擎默认配置目录）
     * @return savepoint 路径（引擎返回的 location）
     * @throws SavepointException 触发失败/超时/引擎拒绝
     */
    String stopWithSavepoint(String restEndpoint, String jobId, String targetDirectory)
            throws SavepointException;

    /** savepoint 触发失败（引擎不可达 / 拒绝 / 超时）。 */
    class SavepointException extends RuntimeException {
        public SavepointException(String message) {
            super(message);
        }
        public SavepointException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
