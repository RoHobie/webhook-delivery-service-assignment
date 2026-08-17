package com.webhook.delivery;

import com.webhook.delivery.domain.Delivery;
import com.webhook.delivery.domain.Endpoint;
import com.webhook.delivery.domain.Tenant;
import com.webhook.delivery.domain.WebhookEvent;
import com.webhook.delivery.repository.DeliveryRepository;
import com.webhook.delivery.repository.EndpointRepository;
import com.webhook.delivery.repository.TenantRepository;
import com.webhook.delivery.repository.WebhookEventRepository;
import com.webhook.delivery.service.DeliveryClaimService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.worker.scheduler-enabled=false"
})
@Testcontainers
class ConcurrencyClaimTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private DeliveryClaimService claimService;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private WebhookEventRepository eventRepository;

    @Test
    @DisplayName("Concurrent workers using SKIP LOCKED must never claim the same delivery row twice")
    void concurrentWorkersNeverClaimSameDeliveryTwice() throws Exception {
        String tenantId = "tenant-concurrency-test";
        tenantRepository.save(Tenant.builder().id(tenantId).name("Tenant Conc").createdAt(OffsetDateTime.now()).build());

        Endpoint ep = endpointRepository.save(Endpoint.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .url("https://example.com/conc")
                .secret("whsec_123")
                .subscribedEventTypes("*")
                .status("ACTIVE")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());

        WebhookEvent event = eventRepository.save(WebhookEvent.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .eventIdExternal("evt_conc_1")
                .type("order.created")
                .payload("{}")
                .createdAt(OffsetDateTime.now())
                .build());

        int totalDeliveries = 50;
        OffsetDateTime now = OffsetDateTime.now();
        List<Delivery> deliveries = new ArrayList<>();
        for (int i = 0; i < totalDeliveries; i++) {
            deliveries.add(Delivery.builder()
                    .id(UUID.randomUUID().toString())
                    .eventId(event.getId())
                    .endpointId(ep.getId())
                    .tenantId(tenantId)
                    .status("PENDING")
                    .attemptCount(0)
                    .nextAttemptAt(now.minusMinutes(1))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        deliveryRepository.saveAll(deliveries);

        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final String workerId = "worker-conc-" + i;
            futures.add(executor.submit(() -> {
                startLatch.await(); // Synchronize thread startup for max contention
                List<Delivery> claimed = claimService.claimDeliveries(workerId, 10);
                return claimed.stream().map(Delivery::getId).toList();
            }));
        }

        startLatch.countDown(); // Release threads simultaneously

        List<String> allClaimedIds = Collections.synchronizedList(new ArrayList<>());
        for (Future<List<String>> future : futures) {
            allClaimedIds.addAll(future.get());
        }
        executor.shutdown();

        assertThat(allClaimedIds).hasSize(totalDeliveries);

        Set<String> uniqueClaimedIds = new HashSet<>(allClaimedIds);
        assertThat(uniqueClaimedIds).hasSize(totalDeliveries); // Zero duplicate claims
    }
}
