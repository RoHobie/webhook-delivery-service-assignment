package com.webhook.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryResponse {

    private String id;
    private String eventId;
    private String endpointId;
    private String tenantId;
    private String status;
    private int attemptCount;
    private Integer lastResponseCode;
    private String lastResponseSnippet;
    private OffsetDateTime nextAttemptAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
