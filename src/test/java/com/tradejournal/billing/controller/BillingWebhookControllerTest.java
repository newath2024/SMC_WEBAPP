package com.tradejournal.billing.controller;

import com.tradejournal.auth.repository.UserRepository;
import com.tradejournal.billing.repository.BillingInvoiceRepository;
import com.tradejournal.billing.repository.BillingSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BillingWebhookControllerTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BillingWebhookController controller = new BillingWebhookController(
                mock(UserRepository.class),
                mock(BillingSubscriptionRepository.class),
                mock(BillingInvoiceRepository.class)
        );
        ReflectionTestUtils.setField(controller, "webhookSecret", WEBHOOK_SECRET);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void handleStripeWebhookRejectsStaleSignature() throws Exception {
        String payload = """
                {"id":"evt_stale","type":"unknown.event","data":{"object":{}}}
                """;
        long timestamp = Instant.now().minusSeconds(600).getEpochSecond();

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signatureHeader(payload, timestamp))
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Invalid webhook signature"));
    }

    @Test
    void handleStripeWebhookAcceptsAnyMatchingV1Signature() throws Exception {
        String payload = """
                {"id":"evt_multi","type":"unknown.event","data":{"object":{}}}
                """;
        long timestamp = Instant.now().getEpochSecond();
        String validSignature = computeSignature(payload, timestamp);
        String header = "t=" + timestamp + ",v1=deadbeef,v1=" + validSignature;

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.handled").value(false))
                .andExpect(jsonPath("$.eventId").value("evt_multi"))
                .andExpect(jsonPath("$.eventType").value("unknown.event"));
    }

    @Test
    void handleStripeWebhookRejectsMalformedTimestamp() throws Exception {
        String payload = """
                {"id":"evt_bad_ts","type":"unknown.event","data":{"object":{}}}
                """;
        String header = "t=not-a-number,v1=" + computeSignature(payload, Instant.now().getEpochSecond());

        mockMvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", header)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Invalid webhook signature"));
    }

    private String signatureHeader(String payload, long timestamp) {
        return "t=" + timestamp + ",v1=" + computeSignature(payload, timestamp);
    }

    private String computeSignature(String payload, long timestamp) {
        String signedPayload = timestamp + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format(Locale.ENGLISH, "%02x", value));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute Stripe test signature", ex);
        }
    }
}
