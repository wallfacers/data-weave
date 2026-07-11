package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.HealthEvent;

import java.time.LocalDateTime;
import java.util.List;

public interface HealthEventRepository {

    /** 去重 upsert：命中 (tenantId,type,fingerprint) → count++/last_occurred_at 刷新；否则插入新行。 */
    void record(HealthEvent e);

    /** 多条件分页查询（按 last_occurred_at 倒序），所有过滤可空。incidentOnly=true 仅返回已关联未关闭工单的信号。 */
    List<HealthEvent> query(long tenantId, String type, String severity, String refKind, String refId,
                            LocalDateTime from, LocalDateTime to, boolean incidentOnly, int offset, int limit);

    int count(long tenantId, String type, String severity, String refKind, String refId,
              LocalDateTime from, LocalDateTime to, boolean incidentOnly);
}
