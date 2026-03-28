package com.tradejournal.billing.controller;

import com.tradejournal.auth.domain.User;
import com.tradejournal.billing.domain.BillingCycle;
import com.tradejournal.billing.domain.BillingInvoice;
import com.tradejournal.billing.domain.BillingPaymentStatus;
import com.tradejournal.billing.domain.BillingPlan;
import com.tradejournal.billing.domain.BillingSubscription;
import com.tradejournal.billing.domain.PlanType;
import com.tradejournal.billing.domain.SubscriptionStatus;
import com.tradejournal.billing.repository.BillingInvoiceRepository;
import com.tradejournal.billing.repository.BillingSubscriptionRepository;
import com.tradejournal.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;

@RestController
@RequestMapping("/webhooks")
public class BillingWebhookController {

    private static final double DEFAULT_PRO_MONTHLY_PRICE = 29.0;
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserRepository userRepository;
    private final BillingSubscriptionRepository billingSubscriptionRepository;
    private final BillingInvoiceRepository billingInvoiceRepository;

    @Value("${billing.webhook.secret:}")
    private String webhookSecret;

    public BillingWebhookController(
            UserRepository userRepository,
            BillingSubscriptionRepository billingSubscriptionRepository,
            BillingInvoiceRepository billingInvoiceRepository
    ) {
        this.userRepository = userRepository;
        this.billingSubscriptionRepository = billingSubscriptionRepository;
        this.billingInvoiceRepository = billingInvoiceRepository;
    }

    @PostMapping(value = "/stripe", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Map<String, Object>> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature
    ) {
        try {
            if (!verifySignature(payload, stripeSignature)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("ok", false, "error", "Invalid webhook signature"));
            }

            JsonNode root = objectMapper.readTree(payload);
            String eventId = root.path("id").asText("");
            String eventType = root.path("type").asText("");
            JsonNode objectNode = root.path("data").path("object");

            if (eventType.isBlank() || objectNode.isMissingNode()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("ok", false, "error", "Invalid webhook payload"));
            }

            boolean handled = handleEvent(eventType, objectNode);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "handled", handled,
                    "eventId", eventId,
                    "eventType", eventType
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("ok", false, "error", ex.getMessage()));
        }
    }

    private boolean handleEvent(String eventType, JsonNode objectNode) {
        return switch (eventType) {
            case "invoice.payment_succeeded" -> {
                handleInvoicePaymentSucceeded(objectNode);
                yield true;
            }
            case "invoice.payment_failed" -> {
                handleInvoicePaymentFailed(objectNode);
                yield true;
            }
            case "charge.refunded", "invoice.refunded" -> {
                handleRefunded(objectNode);
                yield true;
            }
            case "customer.subscription.created", "customer.subscription.updated" -> {
                handleSubscriptionUpsert(objectNode);
                yield true;
            }
            case "customer.subscription.deleted" -> {
                handleSubscriptionDeleted(objectNode);
                yield true;
            }
            default -> false;
        };
    }

    private void handleInvoicePaymentSucceeded(JsonNode invoiceNode) {
        User user = resolveUser(invoiceNode);
        BillingSubscription sub = ensureSubscription(user);

        String customerId = text(invoiceNode, "customer");
        String subscriptionId = text(invoiceNode, "subscription");
        if (!customerId.isBlank()) {
            sub.setProvider("stripe");
            sub.setProviderCustomerId(customerId);
        }
        if (!subscriptionId.isBlank()) {
            sub.setProviderSubscriptionId(subscriptionId);
        }

        double amount = centsToUsd(longValue(invoiceNode, "amount_paid", 0L));
        if (amount <= 0.0) {
            amount = sub.getAmount() > 0 ? sub.getAmount() : DEFAULT_PRO_MONTHLY_PRICE;
        }

        sub.setPlan(BillingPlan.PRO);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setBillingCycle(BillingCycle.MONTHLY);
        sub.setAmount(amount);
        sub.setPaymentStatus(BillingPaymentStatus.PAID);
        sub.setNextBillingDate(extractPeriodEndDate(invoiceNode).orElse(LocalDate.now().plusDays(30)));
        billingSubscriptionRepository.save(sub);

        user.setPlanType(PlanType.PRO);
        user.setActive(true);
        userRepository.save(user);

        upsertInvoice(invoiceNode, user, amount, BillingPaymentStatus.PAID, "Stripe card");
    }

    private void handleInvoicePaymentFailed(JsonNode invoiceNode) {
        User user = resolveUser(invoiceNode);
        BillingSubscription sub = ensureSubscription(user);

        String customerId = text(invoiceNode, "customer");
        String subscriptionId = text(invoiceNode, "subscription");
        if (!customerId.isBlank()) {
            sub.setProvider("stripe");
            sub.setProviderCustomerId(customerId);
        }
        if (!subscriptionId.isBlank()) {
            sub.setProviderSubscriptionId(subscriptionId);
        }

        double amount = centsToUsd(longValue(invoiceNode, "amount_due", 0L));
        if (amount <= 0.0) {
            amount = sub.getAmount() > 0 ? sub.getAmount() : DEFAULT_PRO_MONTHLY_PRICE;
        }

        sub.setPlan(BillingPlan.PRO);
        sub.setStatus(SubscriptionStatus.PAST_DUE);
        sub.setBillingCycle(BillingCycle.MONTHLY);
        sub.setAmount(amount);
        sub.setPaymentStatus(BillingPaymentStatus.FAILED);
        sub.setNextBillingDate(LocalDate.now().plusDays(3));
        billingSubscriptionRepository.save(sub);

        user.setPlanType(PlanType.PRO);
        user.setActive(false);
        userRepository.save(user);

        upsertInvoice(invoiceNode, user, amount, BillingPaymentStatus.FAILED, "Stripe card");
    }

    private void handleRefunded(JsonNode objectNode) {
        User user = resolveUser(objectNode);
        BillingSubscription sub = ensureSubscription(user);

        sub.setPaymentStatus(BillingPaymentStatus.REFUNDED);
        if (sub.getPlan() == BillingPlan.PRO && sub.getStatus() == SubscriptionStatus.ACTIVE) {
            sub.setStatus(SubscriptionStatus.PAST_DUE);
        }
        billingSubscriptionRepository.save(sub);

        double amount = centsToUsd(longValue(objectNode, "amount_refunded", longValue(objectNode, "amount", 0L)));
        if (amount <= 0.0) {
            amount = sub.getAmount();
        }

        upsertInvoice(objectNode, user, amount, BillingPaymentStatus.REFUNDED, "Stripe refund");
    }

    private void handleSubscriptionUpsert(JsonNode subNode) {
        User user = resolveUser(subNode);
        BillingSubscription sub = ensureSubscription(user);

        String customerId = text(subNode, "customer");
        String subscriptionId = text(subNode, "id");
        if (!customerId.isBlank()) {
            sub.setProvider("stripe");
            sub.setProviderCustomerId(customerId);
        }
        if (!subscriptionId.isBlank()) {
            sub.setProviderSubscriptionId(subscriptionId);
        }

        String stripeStatus = text(subNode, "status").toLowerCase(Locale.ENGLISH);
        SubscriptionStatus mappedStatus;
        BillingPlan mappedPlan;
        BillingPaymentStatus paymentStatus;

        switch (stripeStatus) {
            case "active" -> {
                mappedStatus = SubscriptionStatus.ACTIVE;
                mappedPlan = BillingPlan.PRO;
                paymentStatus = BillingPaymentStatus.PAID;
            }
            case "trialing" -> {
                mappedStatus = SubscriptionStatus.TRIALING;
                mappedPlan = BillingPlan.TRIAL;
                paymentStatus = BillingPaymentStatus.PENDING;
            }
            case "past_due", "unpaid", "incomplete" -> {
                mappedStatus = SubscriptionStatus.PAST_DUE;
                mappedPlan = BillingPlan.PRO;
                paymentStatus = BillingPaymentStatus.FAILED;
            }
            case "canceled" -> {
                mappedStatus = SubscriptionStatus.CANCELED;
                mappedPlan = BillingPlan.FREE;
                paymentStatus = BillingPaymentStatus.PENDING;
            }
            default -> {
                mappedStatus = SubscriptionStatus.ACTIVE;
                mappedPlan = BillingPlan.PRO;
                paymentStatus = BillingPaymentStatus.PENDING;
            }
        }

        double planAmount = resolvePlanAmount(subNode, mappedPlan);
        sub.setPlan(mappedPlan);
        sub.setStatus(mappedStatus);
        sub.setBillingCycle(mappedPlan == BillingPlan.PRO ? BillingCycle.MONTHLY : (mappedPlan == BillingPlan.TRIAL ? BillingCycle.TRIAL : BillingCycle.NONE));
        sub.setAmount(planAmount);
        sub.setPaymentStatus(paymentStatus);
        sub.setNextBillingDate(extractCurrentPeriodEnd(subNode).orElseGet(() -> mappedPlan == BillingPlan.PRO ? LocalDate.now().plusDays(30) : null));
        billingSubscriptionRepository.save(sub);

        applyUserPlan(user, mappedPlan, mappedStatus);
    }

    private void handleSubscriptionDeleted(JsonNode subNode) {
        User user = resolveUser(subNode);
        BillingSubscription sub = ensureSubscription(user);

        String customerId = text(subNode, "customer");
        String subscriptionId = text(subNode, "id");
        if (!customerId.isBlank()) {
            sub.setProvider("stripe");
            sub.setProviderCustomerId(customerId);
        }
        if (!subscriptionId.isBlank()) {
            sub.setProviderSubscriptionId(subscriptionId);
        }

        sub.setPlan(BillingPlan.FREE);
        sub.setStatus(SubscriptionStatus.CANCELED);
        sub.setBillingCycle(BillingCycle.NONE);
        sub.setAmount(0.0);
        sub.setPaymentStatus(BillingPaymentStatus.PENDING);
        sub.setNextBillingDate(null);
        billingSubscriptionRepository.save(sub);

        user.setPlanType(PlanType.STANDARD);
        user.setActive(true);
        userRepository.save(user);
    }

    private void applyUserPlan(User user, BillingPlan plan, SubscriptionStatus status) {
        if (plan == BillingPlan.PRO) {
            user.setPlanType(PlanType.PRO);
            user.setActive(status != SubscriptionStatus.PAST_DUE);
        } else {
            user.setPlanType(PlanType.STANDARD);
            user.setActive(true);
        }
        userRepository.save(user);
    }

    private User resolveUser(JsonNode objectNode) {
        JsonNode metadata = objectNode.path("metadata");
        String userId = metadata.path("userId").asText("");
        if (!userId.isBlank()) {
            return userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found by metadata.userId"));
        }

        String customerId = text(objectNode, "customer");
        if (!customerId.isBlank()) {
            Optional<BillingSubscription> byCustomer = billingSubscriptionRepository.findByProviderCustomerId(customerId);
            if (byCustomer.isPresent() && byCustomer.get().getUser() != null) {
                return byCustomer.get().getUser();
            }
        }

        String email = text(objectNode, "customer_email");
        if (email.isBlank()) {
            email = text(objectNode, "receipt_email");
        }
        if (!email.isBlank()) {
            return userRepository.findByEmailIgnoreCase(email)
                    .orElseThrow(() -> new IllegalArgumentException("User not found by email"));
        }

        throw new IllegalArgumentException("Unable to resolve user from webhook payload");
    }

    private BillingSubscription ensureSubscription(User user) {
        return billingSubscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    BillingSubscription sub = new BillingSubscription();
                    sub.setUser(user);
                    sub.setPlan(BillingPlan.FREE);
                    sub.setStatus(SubscriptionStatus.ACTIVE);
                    sub.setBillingCycle(BillingCycle.NONE);
                    sub.setStartDate(user.getCreatedAt() != null ? user.getCreatedAt().toLocalDate() : LocalDate.now());
                    sub.setAmount(0.0);
                    sub.setPaymentStatus(BillingPaymentStatus.PENDING);
                    return billingSubscriptionRepository.save(sub);
                });
    }

    private void upsertInvoice(JsonNode objectNode, User user, double amount, BillingPaymentStatus status, String paymentMethod) {
        String stripeInvoiceId = text(objectNode, "id");
        String invoiceNo = stripeInvoiceId.isBlank()
                ? "STRIPE-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + Math.floorMod(user.getId().hashCode(), 10000)
                : "STRIPE-" + stripeInvoiceId;

        if (billingInvoiceRepository.existsByInvoiceNo(invoiceNo)) {
            BillingInvoice existing = billingInvoiceRepository.findByInvoiceNo(invoiceNo).orElse(null);
            if (existing != null) {
                existing.setStatus(status);
                existing.setAmount(amount);
                existing.setPaymentMethod(paymentMethod);
                billingInvoiceRepository.save(existing);
            }
            return;
        }

        LocalDate invoiceDate = extractTimestampDate(objectNode, "created").orElse(LocalDate.now());
        String billingPeriod = extractBillingPeriod(objectNode, invoiceDate);

        BillingInvoice invoice = new BillingInvoice();
        invoice.setInvoiceNo(invoiceNo);
        invoice.setUser(user);
        invoice.setAmount(amount);
        invoice.setInvoiceDate(invoiceDate);
        invoice.setBillingPeriod(billingPeriod);
        invoice.setStatus(status);
        invoice.setPaymentMethod(paymentMethod);
        invoice.setDownloadLabel("PDF");
        billingInvoiceRepository.save(invoice);
    }

    private boolean verifySignature(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return true;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        SignatureParts parts = parseSignatureHeader(signatureHeader);
        if (parts.timestamp() == null || parts.v1Signatures().isEmpty()) {
            return false;
        }

        long timestamp = parseTimestamp(parts.timestamp());
        if (timestamp <= 0L || isTimestampOutsideTolerance(timestamp)) {
            return false;
        }

        String signedPayload = parts.timestamp() + "." + payload;
        String computed = hmacSha256Hex(webhookSecret, signedPayload);
        return parts.v1Signatures().stream().anyMatch(candidate -> signatureMatches(computed, candidate));
    }

    private SignatureParts parseSignatureHeader(String signatureHeader) {
        String timestamp = null;
        List<String> v1Signatures = new ArrayList<>();

        for (String token : signatureHeader.split(",")) {
            String trimmed = token == null ? "" : token.trim();
            int separatorIndex = trimmed.indexOf('=');
            if (separatorIndex <= 0 || separatorIndex >= trimmed.length() - 1) {
                continue;
            }

            String key = trimmed.substring(0, separatorIndex).trim();
            String value = trimmed.substring(separatorIndex + 1).trim();
            if (value.isBlank()) {
                continue;
            }

            if ("t".equals(key) && timestamp == null) {
                timestamp = value;
            } else if ("v1".equals(key)) {
                v1Signatures.add(value);
            }
        }

        return new SignatureParts(timestamp, List.copyOf(v1Signatures));
    }

    private long parseTimestamp(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private boolean isTimestampOutsideTolerance(long timestamp) {
        long now = Instant.now().getEpochSecond();
        return Math.abs(now - timestamp) > SIGNATURE_TOLERANCE_SECONDS;
    }

    private boolean signatureMatches(String computed, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                candidate.trim().toLowerCase(Locale.ENGLISH).getBytes(StandardCharsets.UTF_8)
        );
    }

    private String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ENGLISH, "%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to verify signature", ex);
        }
    }

    private Optional<LocalDate> extractTimestampDate(JsonNode node, String field) {
        long value = longValue(node, field, 0L);
        if (value <= 0) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(value).atZone(ZoneOffset.UTC).toLocalDate());
    }

    private Optional<LocalDate> extractPeriodEndDate(JsonNode invoiceNode) {
        long end = invoiceNode.path("lines").path("data").path(0).path("period").path("end").asLong(0L);
        if (end <= 0L) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(end).atZone(ZoneOffset.UTC).toLocalDate());
    }

    private Optional<LocalDate> extractCurrentPeriodEnd(JsonNode subNode) {
        long end = longValue(subNode, "current_period_end", 0L);
        if (end <= 0L) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(end).atZone(ZoneOffset.UTC).toLocalDate());
    }

    private String extractBillingPeriod(JsonNode objectNode, LocalDate fallbackDate) {
        long start = objectNode.path("lines").path("data").path(0).path("period").path("start").asLong(0L);
        LocalDate date = start > 0L
                ? Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC).toLocalDate()
                : fallbackDate;
        return date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + date.getYear();
    }

    private double resolvePlanAmount(JsonNode subNode, BillingPlan mappedPlan) {
        if (mappedPlan != BillingPlan.PRO) {
            return 0.0;
        }

        long cents = subNode.path("items").path("data").path(0).path("price").path("unit_amount").asLong(0L);
        if (cents <= 0L) {
            return DEFAULT_PRO_MONTHLY_PRICE;
        }
        return centsToUsd(cents);
    }

    private long longValue(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return value.asLong(fallback);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private double centsToUsd(long cents) {
        return Math.round((cents / 100.0) * 100.0) / 100.0;
    }

    private record SignatureParts(String timestamp, List<String> v1Signatures) {
    }
}
