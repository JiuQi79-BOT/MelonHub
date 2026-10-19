package com.melonhub.client;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"type\":\"user.disabled\",\"applicationId\":2,\"data\":{\"externalId\":\"u1\"}}";

    @Test
    void roundTripSucceeds() {
        long now = Instant.now().getEpochSecond();
        String header = WebhookSignatureVerifier.sign(SECRET, now, BODY);
        assertThat(WebhookSignatureVerifier.verify(SECRET, header, BODY, 300)).isTrue();
    }

    @Test
    void rejectsWrongSecret() {
        String header = WebhookSignatureVerifier.sign("other", Instant.now().getEpochSecond(), BODY);
        assertThat(WebhookSignatureVerifier.verify(SECRET, header, BODY, 300)).isFalse();
    }

    @Test
    void rejectsTimestampOutsideTolerance() {
        long old = Instant.now().getEpochSecond() - 600;
        String header = WebhookSignatureVerifier.sign(SECRET, old, BODY);
        assertThat(WebhookSignatureVerifier.verify(SECRET, header, BODY, 300)).isFalse();
    }

    @Test
    void rejectsTamperedBody() {
        String header = WebhookSignatureVerifier.sign(SECRET, Instant.now().getEpochSecond(), BODY);
        assertThat(WebhookSignatureVerifier.verify(SECRET, header, BODY.replace("u1", "u2"), 300)).isFalse();
    }

    @Test
    void rejectsMalformedHeader() {
        assertThat(WebhookSignatureVerifier.verify(SECRET, null, BODY, 300)).isFalse();
        assertThat(WebhookSignatureVerifier.verify(SECRET, "v1=abc", BODY, 300)).isFalse();
        assertThat(WebhookSignatureVerifier.verify(SECRET, "t=abc,v1=def", BODY, 300)).isFalse();
    }
}
