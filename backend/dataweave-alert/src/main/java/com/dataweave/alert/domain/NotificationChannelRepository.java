package com.dataweave.alert.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface NotificationChannelRepository extends CrudRepository<NotificationChannel, Long> {

    List<NotificationChannel> findByEnabled(Integer enabled);
}
