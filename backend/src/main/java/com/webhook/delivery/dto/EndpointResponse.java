package com.webhook.delivery.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EndpointResponse {

    private String id;
    private String tenantId;
    private String url;
    private String secret;
    private List<String> subscribedEventTypes;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
