package com.webhook.delivery.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureServiceTest {

    private final SignatureService signatureService = new SignatureService();

    @Test
    void shouldGenerateValidSignatureHeader() {
        String secret = "whsec_test123456789";
        String payload = "{\"event\":\"invoice.paid\",\"amount\":5000}";

        SignatureService.SignedHeaders headers = signatureService.generateSignature(secret, payload);

        assertThat(headers.timestamp()).isNotNull();
        assertThat(headers.signature()).startsWith("t=" + headers.timestamp() + ",v1=");
        assertThat(headers.signature()).hasSizeGreaterThan(40);
    }
}
