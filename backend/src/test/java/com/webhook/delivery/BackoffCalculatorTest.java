package com.webhook.delivery;

import com.webhook.delivery.service.BackoffCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffCalculatorTest {

    private final BackoffCalculator calculator = new BackoffCalculator(5, 86400, 8);

    @Test
    @DisplayName("Exponential backoff must generally increase between attempts and respect max delay limit")
    void testBackoffIncreasesAndRespectsBounds() {
        long delay1 = calculator.calculateNextDelaySeconds(1);
        long delay2 = calculator.calculateNextDelaySeconds(2);
        long delay3 = calculator.calculateNextDelaySeconds(3);

        assertThat(delay1).isGreaterThanOrEqualTo(2);
        assertThat(delay2).isGreaterThanOrEqualTo(delay1 / 2);
        assertThat(delay3).isGreaterThan(0);

        // Test max delay cap
        long largeAttemptDelay = calculator.calculateNextDelaySeconds(20);
        assertThat(largeAttemptDelay).isLessThanOrEqualTo(86400);
    }
}
