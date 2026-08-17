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
public class IngestEventResponse {

    private String id;
    private String eventId;
    private String status;
    private int deliveriesCreated;
    private OffsetDateTime createdAt;
}
