package com.webhook.delivery.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

@Service
public class SignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    public SignedHeaders generateSignature(String secret, String payloadJson) {
        long timestamp = Instant.now().getEpochSecond();
        String timestampStr = String.valueOf(timestamp);
        String payloadToSign = timestampStr + "." + payloadJson;

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(payloadToSign.getBytes(StandardCharsets.UTF_8));
            String signatureHex = HexFormat.of().formatHex(hmacBytes);
            String signatureHeader = "t=" + timestampStr + ",v1=" + signatureHex;

            return new SignedHeaders(signatureHeader, timestampStr);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Error generating HMAC-SHA256 signature: " + e.getMessage(), e);
        }
    }

    public record SignedHeaders(String signature, String timestamp) {}
}
