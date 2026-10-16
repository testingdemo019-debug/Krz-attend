package com.krztechnologies.attend.notification.repository;

import com.krztechnologies.attend.notification.domain.NotificationRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, String> {}
