package com.webhook.delivery.service;

import com.webhook.delivery.repository.DeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class CrashRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(CrashRecoveryService.class);

    private final DeliveryRepository deliveryRepository;

    public CrashRecoveryService(DeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    @Scheduled(fixedDelayString = "${app.worker.cleanup-interval-ms:30000}")
    @Transactional
    public int releaseStaleLocks() {
        OffsetDateTime now = OffsetDateTime.now();
        int releasedCount = deliveryRepository.releaseStaleLocks(now);
        if (releasedCount > 0) {
            log.info("Crash recovery: released {} stale delivery lock(s)", releasedCount);
        }
        return releasedCount;
    }
}
