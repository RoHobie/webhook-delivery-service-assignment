package com.webhook.delivery.service;

import com.webhook.delivery.domain.Delivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class WorkerSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(WorkerSchedulerService.class);

    private final DeliveryClaimService claimService;
    private final DeliveryDispatchService dispatchService;
    private final String workerId;
    private final int batchSize;
    private final ExecutorService executorService;
    private final boolean schedulerEnabled;

    public WorkerSchedulerService(
            DeliveryClaimService claimService,
            DeliveryDispatchService dispatchService,
            @Value("${app.worker.batch-size:50}") int batchSize,
            @Value("${app.worker.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.claimService = claimService;
        this.dispatchService = dispatchService;
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        this.batchSize = batchSize;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.schedulerEnabled = schedulerEnabled;
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-interval-ms:1000}")
    public void pollAndProcessDeliveries() {
        if (!schedulerEnabled) {
            return;
        }

        try {
            List<Delivery> claimed = claimService.claimDeliveries(workerId, batchSize);
            if (claimed.isEmpty()) {
                return;
            }

            log.debug("Worker {} claimed {} due deliveries", workerId, claimed.size());
            for (Delivery delivery : claimed) {
                executorService.submit(() -> {
                    try {
                        dispatchService.processDelivery(delivery.getId());
                    } catch (Exception e) {
                        log.error("Error processing delivery {}: {}", delivery.getId(), e.getMessage(), e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Error during delivery claim poll: {}", e.getMessage(), e);
        }
    }
}
