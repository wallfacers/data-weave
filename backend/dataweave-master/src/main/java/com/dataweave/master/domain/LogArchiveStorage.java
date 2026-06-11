package com.dataweave.master.domain;

import java.util.Optional;

/**
 * 日志归档存储接缝（design D11）：任务结束后整段日志归档到对象存储，历史日志/`dw logs cat` 走此读取。
 *
 * <p>归档键约定 {@code logs/{biz_date}/{instance_id}/{attempt}.log}。all-in-one 默认 {@code file}
 * 实现（本地目录）；distributed 用 MinIO（S3 API）。{@code task_instance.log} 只存尾部摘要。
 */
public interface LogArchiveStorage {

    /** 写入（覆盖）归档对象。 */
    void put(String key, String content);

    /** 读取归档对象；不存在返回空。 */
    Optional<String> get(String key);

    /** 归档对象是否存在。 */
    boolean exists(String key);
}
