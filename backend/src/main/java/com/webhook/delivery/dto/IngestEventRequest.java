package com.webhook.delivery.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestEventRequest {

    @NotBlank(message = "eventId is required")
    private String eventId;

    @NotBlank(message = "type is required")
    private String type;

    @NotNull(message = "payload is required")
    private JsonNode payload;
}
