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
public class EndpointTestResultResponse {

    private String endpointId;
    private boolean success;
    private Integer statusCode;
    private long latencyMs;
    private String responseSnippet;
    private String error;
    private OffsetDateTime testedAt;
}
