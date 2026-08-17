package com.webhook.delivery.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateEndpointRequest {

    @NotNull(message = "URL is required")
    private String url;

    @NotEmpty(message = "subscribedEventTypes cannot be empty")
    private List<String> subscribedEventTypes;
}
