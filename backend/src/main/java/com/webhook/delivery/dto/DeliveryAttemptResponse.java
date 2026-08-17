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
public class DeliveryAttemptResponse {

    private String id;
    private String deliveryId;
    private int attemptNumber;
    private Integer responseCode;
    private long latencyMs;
    private String error;
    private OffsetDateTime createdAt;
}
