package com.webhook.delivery.service;

import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.repository.DeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class DeliveryClaimService {

    private final DeliveryRepository deliveryRepository;
    private final int leaseDurationSeconds;

    public DeliveryClaimService(
            DeliveryRepository deliveryRepository,
            @Value("${app.worker.lease-duration-seconds:30}") int leaseDurationSeconds) {
        this.deliveryRepository = deliveryRepository;
        this.leaseDurationSeconds = leaseDurationSeconds;
    }

    @Transactional
    public List<Delivery> claimDeliveries(String workerId, int limit) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Delivery> dueDeliveries = deliveryRepository.claimDueDeliveriesNative(now, limit);

        if (dueDeliveries.isEmpty()) {
            return List.of();
        }

        OffsetDateTime lockedUntil = now.plusSeconds(leaseDurationSeconds);
        for (Delivery d : dueDeliveries) {
            d.setLockedBy(workerId);
            d.setLockedUntil(lockedUntil);
            d.setUpdatedAt(now);
        }

        return deliveryRepository.saveAll(dueDeliveries);
    }
}
