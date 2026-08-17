package com.webhook.delivery.repository;

import com.webhook.delivery.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, String> {

    List<DeliveryAttempt> findAllByDeliveryIdOrderByAttemptNumberAsc(String deliveryId);
}
