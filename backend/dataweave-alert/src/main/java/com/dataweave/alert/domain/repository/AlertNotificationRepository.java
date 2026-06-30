package com.dataweave.alert.domain.repository;

import com.dataweave.alert.domain.AlertNotification;
import java.util.List;

public interface AlertNotificationRepository {
    AlertNotification save(AlertNotification notification);
    List<AlertNotification> findByEventId(Long tenantId, Long eventId);
    int updateStatus(Long id, String status, String error, String responseDigest);
}
