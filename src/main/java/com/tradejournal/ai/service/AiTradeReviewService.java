package com.tradejournal.service;

import com.tradejournal.entity.MistakeTag;
import com.tradejournal.entity.Trade;
import com.tradejournal.entity.TradeImage;
import com.tradejournal.entity.TradeReview;
import com.tradejournal.repository.TradeReviewRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class AiTradeReviewService {

    private static final Logger log = LoggerFactory.getLogger(AiTradeReviewService.class);
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String IMAGE_URL_PREFIX = "/uploads/trade-images/";
    private static final Path IMAGE_STORAGE_DIR = Path.of("data", "uploads", "trade-images");
    private static final int MAX_IMAGE_COUNT = 6;
    private static final long MAX_TOTAL_IMAGE_BYTES = 18L * 1024L * 1024L;

    private static final String REVIEW_PROMPT = """
            You are an AI assistant for reviewing a completed trade journal entry.

            You will receive:
            - structured trade data
            - the trader's written notes
            - the trader's manual self review answers if available
            - chart images if available

            Your job is to produce a concise, evidence-based AI review.

            Requirements:
            - Focus on trading process quality, execution quality, and review quality.
            - Be specific, practical, and concise.
            - Do not invent facts that are not supported by the provided trade data, notes, self review, or charts.
            - If evidence is weak, reduce confidence and keep lists shorter.
            - Suggested mistake tags must be short reusable tags, for example: FOMO, early exit, no confirmation, news trade, overrisk, poor session selection.
            - Avoid repeating the same idea in multiple sections.

            Return JSON only with:
            - summary: string
            - strengths: array of short bullet points
            - weaknesses: array of short bullet points
            - improvements: array of short action points
            - suggestedMistakeTags: array of short tags
            - confidence: low, medium, or high
            - processScore: integer from 0 to 100

            Guidelines for processScore:
            - 85 to 100 = strong process, strong discipline
            - 70 to 84 = mostly good process with some issues
            - below 70 = notable process problems

            If there is not enough evidence, still return a helpful review but keep confidence low and keep processScore conservative.
            """;

    private final TradeReviewRepository tradeReviewRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;

    public AiTradeReviewService(
            TradeReviewRepository tradeReviewRepository,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.trade-review-model:gpt-4.1-mini}") String model,
            @Value("${openai.trade-review-timeout-seconds:90}") long timeoutSeconds
    ) {
        this.tradeReviewRepository = tradeReviewRepository;
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = StringUtils.hasText(model) ? model.trim() : "gpt-4.1-mini";
        this.timeout = Duration.ofSeconds(Math.max(15L, timeoutSeconds));
    }

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }

    public AiReviewView resolveView(TradeReview review) {
        if (review == null || !review.hasAiReview()) {
            return AiReviewView.empty();
        }

        return new AiReviewView(
                "success",
                trimToNull(review.getAiSummary()),
                readList(review.getAiStrengthsJson()),
                readList(review.getAiWeaknessesJson()),
                readList(review.getAiImprovementsJson()),
                readList(review.getAiSuggestedMistakeTagsJson()),
                trimToNull(review.getAiConfidence()),
                trimToNull(review.getAiModel()),
                review.getAiGeneratedAt(),
                null,
                review.getAiProcessScore()
        );
    }

    @Transactional
    public AiReviewView generateForTrade(Trade trade, TradeReview existingReview, List<TradeImage> images) {
        if (trade == null || trade.getId() == null) {
            throw new IllegalArgumentException("Trade not found.");
        }
        if (!isConfigured()) {
            throw new IllegalStateException("AI Review is not configured. Set OPENAI_API_KEY first.");
        }

        TradeReview review = existingReview != null ? existingReview : new TradeReview();
        review.setTrade(trade);

        List<String> imageDataUrls = loadImageDataUrls(images);
        String requestBody = buildRequestBody(trade, review, imageDataUrls);
        URI endpointUri = URI.create(baseUrl + "/responses");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpointUri)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            log.info("Submitting AI trade review to OpenAI. endpoint='{}', model='{}', tradeId='{}', imageCount={}.",
                    endpointUri, model, trade.getId(), imageDataUrls.size());

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                String errorMessage = resolveOpenAiErrorMessage(response.body(), response.statusCode());
                log.warn("OpenAI AI review failed. endpoint='{}', model='{}', tradeId='{}', status={}, message='{}', bodySnippet='{}'.",
                        endpointUri, model, trade.getId(), response.statusCode(), errorMessage, summarizeBody(response.body()));
                throw new RuntimeException(errorMessage);
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            String outputJson = extractOutputJson(responseJson);
            if (!StringUtils.hasText(outputJson)) {
                throw new RuntimeException("OpenAI did not return an AI review payload.");
            }

            RawAiReview raw = objectMapper.readValue(outputJson, RawAiReview.class);
            AiReviewView view = normalizeView(raw);

            review.setAiSummary(view.summary());
            review.setAiStrengthsJson(writeList(view.strengths()));
            review.setAiWeaknessesJson(writeList(view.weaknesses()));
            review.setAiImprovementsJson(writeList(view.improvements()));
            review.setAiSuggestedMistakeTagsJson(writeList(view.suggestedMistakeTags()));
            review.setAiConfidence(view.confidence());
            review.setAiModel(model);
            review.setAiProcessScore(view.processScore());
            review.setAiGeneratedAt(LocalDateTime.now());

            TradeReview saved = tradeReviewRepository.save(review);
            return resolveView(saved);
        } catch (IOException ex) {
            log.error("Unable to parse AI review response for tradeId='{}'.", trade.getId(), ex);
            throw new RuntimeException("Unable to read the AI review response.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("AI review generation was interrupted.", ex);
        }
    }

    private String buildRequestBody(Trade trade, TradeReview review, List<String> imageDataUrls) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("max_output_tokens", 900);

        ArrayNode input = payload.putArray("input");

        ObjectNode systemMessage = input.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", REVIEW_PROMPT);

        ObjectNode userMessage = input.addObject();
        userMessage.put("role", "user");
        ArrayNode userContent = userMessage.putArray("content");

        ObjectNode textPart = userContent.addObject();
        textPart.put("type", "input_text");
        textPart.put("text", buildTradeContext(trade, review, imageDataUrls.size()));

        for (String dataUrl : imageDataUrls) {
            ObjectNode imagePart = userContent.addObject();
            imagePart.put("type", "input_image");
            imagePart.put("image_url", dataUrl);
            imagePart.put("detail", "high");
        }

        ObjectNode text = payload.putObject("text");
        ObjectNode format = text.putObject("format");
        format.put("type", "json_schema");
        format.put("name", "trade_ai_review");
        format.put("strict", true);
        format.set("schema", buildSchema());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to prepare the AI review request.", ex);
        }
    }

    private String buildTradeContext(Trade trade, TradeReview review, int imageCount) {
        StringBuilder builder = new StringBuilder();
        builder.append("Review this completed trade.\n\n");
        builder.append("Structured trade data:\n");
        builder.append("- Symbol: ").append(orDash(trade.getSymbol())).append('\n');
        builder.append("- Direction: ").append(orDash(trade.getDirection())).append('\n');
        builder.append("- Result: ").append(orDash(trade.getResult())).append('\n');
        builder.append("- Setup: ").append(orDash(trade.getSetupName())).append('\n');
        builder.append("- Session: ").append(orDash(trade.getSession())).append('\n');
        builder.append("- HTF: ").append(orDash(trade.getHtf())).append('\n');
        builder.append("- LTF: ").append(orDash(trade.getLtf())).append('\n');
        builder.append("- Entry price: ").append(trade.getEntryPrice()).append('\n');
        builder.append("- Stop loss: ").append(trade.getStopLoss()).append('\n');
        builder.append("- Initial stop loss: ").append(trade.getInitialStopLoss() != null ? trade.getInitialStopLoss() : "-").append('\n');
        builder.append("- Take profit: ").append(trade.getTakeProfit()).append('\n');
        builder.append("- Exit price: ").append(trade.getExitPrice()).append('\n');
        builder.append("- Position size: ").append(trade.getPositionSize()).append('\n');
        builder.append("- PnL: ").append(trade.getPnl()).append('\n');
        builder.append("- R multiple: ").append(trade.hasKnownRMultiple() ? trade.getRMultiple() + " (" + trade.getRMultipleSourceLabel() + ")" : "unknown").append('\n');
        builder.append("- Exact entry time: ").append(trade.getEntryTime() != null ? trade.getEntryTime() : "-").append('\n');
        builder.append("- Exact exit time: ").append(trade.getExitTime() != null ? trade.getExitTime() : "-").append('\n');
        builder.append("- Estimated holding minutes: ").append(trade.getEstimatedHoldingMinutes() != null ? trade.getEstimatedHoldingMinutes() : "-").append('\n');
        builder.append("- Notes: ").append(orDash(trade.getNote())).append('\n');
        builder.append("- Current mistake tags: ").append(joinMistakeNames(trade.getMistakes())).append('\n');
        builder.append('\n');

        builder.append("Manual self review:\n");
        builder.append("- Followed plan: ").append(formatBoolean(review.getFollowedPlan())).append('\n');
        builder.append("- Had confirmation: ").append(formatBoolean(review.getHadConfirmation())).append('\n');
        builder.append("- Respected risk: ").append(formatBoolean(review.getRespectedRisk())).append('\n');
        builder.append("- HTF bias aligned: ").append(formatBoolean(review.getAlignedHtfBias())).append('\n');
        builder.append("- Correct session: ").append(formatBoolean(review.getCorrectSession())).append('\n');
        builder.append("- Correct setup: ").append(formatBoolean(review.getCorrectSetup())).append('\n');
        builder.append("- Correct POI: ").append(formatBoolean(review.getCorrectPoi())).append('\n');
        builder.append("- Had FOMO: ").append(formatBoolean(review.getHadFomo())).append('\n');
        builder.append("- Entered before news: ").append(formatBoolean(review.getEnteredBeforeNews())).append('\n');
        builder.append("- Entry timing rating: ").append(review.getEntryTimingRating() != null ? review.getEntryTimingRating() : "-").append('\n');
        builder.append("- Exit quality rating: ").append(review.getExitQualityRating() != null ? review.getExitQualityRating() : "-").append('\n');
        builder.append("- Would take again: ").append(formatBoolean(review.getWouldTakeAgain())).append('\n');
        builder.append("- Pre-trade checklist: ").append(orDash(review.getPreTradeChecklist())).append('\n');
        builder.append("- Post-trade review: ").append(orDash(review.getPostTradeReview())).append('\n');
        builder.append("- Lesson learned: ").append(orDash(review.getLessonLearned())).append('\n');
        builder.append("- Improvement note: ").append(orDash(review.getImprovementNote())).append('\n');
        builder.append('\n');

        builder.append("Chart images attached: ").append(imageCount).append(". Use them only as supporting context for process review and execution quality.\n");
        return builder.toString();
    }

    private ObjectNode buildSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        addRequiredString(properties, "summary", "Short overall summary of the trade review.");
        addRequiredStringArray(properties, "strengths", "Observed strengths in the trade process.");
        addRequiredStringArray(properties, "weaknesses", "Observed weaknesses in the trade process.");
        addRequiredStringArray(properties, "improvements", "Practical improvements for next time.");
        addRequiredStringArray(properties, "suggestedMistakeTags", "Short mistake tag suggestions.");
        addRequiredEnum(properties, "confidence", "Global confidence of this AI review.", "low", "medium", "high");
        addRequiredInteger(properties, "processScore", "Estimated process quality score from 0 to 100.");

        ArrayNode required = schema.putArray("required");
        required.add("summary");
        required.add("strengths");
        required.add("weaknesses");
        required.add("improvements");
        required.add("suggestedMistakeTags");
        required.add("confidence");
        required.add("processScore");
        schema.put("additionalProperties", false);
        return schema;
    }

    private void addRequiredString(ObjectNode properties, String name, String description) {
        ObjectNode field = properties.putObject(name);
        field.put("type", "string");
        field.put("description", description);
    }

    private void addRequiredStringArray(ObjectNode properties, String name, String description) {
        ObjectNode field = properties.putObject(name);
        field.put("type", "array");
        field.put("description", description);
        ObjectNode items = field.putObject("items");
        items.put("type", "string");
    }

    private void addRequiredEnum(ObjectNode properties, String name, String description, String... values) {
        ObjectNode field = properties.putObject(name);
        field.put("type", "string");
        field.put("description", description);
        ArrayNode enumValues = field.putArray("enum");
        for (String value : values) {
            enumValues.add(value);
        }
    }

    private void addRequiredInteger(ObjectNode properties, String name, String description) {
        ObjectNode field = properties.putObject(name);
        field.put("type", "integer");
        field.put("description", description);
        field.put("minimum", 0);
        field.put("maximum", 100);
    }

    private List<String> loadImageDataUrls(List<TradeImage> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }

        List<String> dataUrls = new ArrayList<>();
        long totalBytes = 0L;

        for (TradeImage image : images) {
            if (image == null || !StringUtils.hasText(image.getImageUrl()) || dataUrls.size() >= MAX_IMAGE_COUNT) {
                continue;
            }

            Path path = resolveImagePath(image.getImageUrl());
            if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
                continue;
            }

            try {
                byte[] bytes = Files.readAllBytes(path);
                totalBytes += bytes.length;
                if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                    break;
                }
                String mimeType = detectMimeType(path);
                dataUrls.add("data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes));
            } catch (IOException ignored) {
            }
        }

        return dataUrls;
    }

    private Path resolveImagePath(String imageUrl) {
        if (!StringUtils.hasText(imageUrl) || !imageUrl.startsWith(IMAGE_URL_PREFIX)) {
            return null;
        }
        String filename = imageUrl.substring(IMAGE_URL_PREFIX.length());
        if (!StringUtils.hasText(filename)) {
            return null;
        }
        return IMAGE_STORAGE_DIR.resolve(filename);
    }

    private String detectMimeType(Path path) {
        String fileName = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        if (fileName.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private String extractOutputJson(JsonNode responseJson) {
        String directOutput = responseJson.path("output_text").asText("");
        if (StringUtils.hasText(directOutput)) {
            return stripMarkdownFence(directOutput.trim());
        }

        JsonNode output = responseJson.path("output");
        if (!output.isArray()) {
            return "";
        }

        for (JsonNode item : output) {
            JsonNode content = item.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode contentItem : content) {
                String text = contentItem.path("text").asText("");
                if (StringUtils.hasText(text)) {
                    return stripMarkdownFence(text.trim());
                }
            }
        }
        return "";
    }

    private String stripMarkdownFence(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak >= 0) {
                trimmed = trimmed.substring(firstLineBreak + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private AiReviewView normalizeView(RawAiReview raw) {
        return new AiReviewView(
                "success",
                trimToNull(raw.summary()),
                normalizeList(raw.strengths()),
                normalizeList(raw.weaknesses()),
                normalizeList(raw.improvements()),
                normalizeList(raw.suggestedMistakeTags()),
                normalizeConfidence(raw.confidence()),
                model,
                LocalDateTime.now(),
                null,
                normalizeProcessScore(raw.processScore())
        );
    }

    private Integer normalizeProcessScore(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.max(0, Math.min(100, value));
    }

    private String normalizeConfidence(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "medium", "high" -> normalized;
            default -> normalized;
        };
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .map(this::trimToNull)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(6)
                .collect(Collectors.toList());
        return normalized.isEmpty() ? List.of() : normalized;
    }

    private List<String> readList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return normalizeList(values);
        } catch (IOException ex) {
            return List.of();
        }
    }

    private String writeList(List<String> values) {
        List<String> normalized = normalizeList(values);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to store AI review list data.", ex);
        }
    }

    private String resolveOpenAiErrorMessage(String responseBody, int statusCode) {
        try {
            JsonNode errorBody = objectMapper.readTree(responseBody);
            String message = errorBody.path("error").path("message").asText("");
            if (StringUtils.hasText(message)) {
                return message;
            }
        } catch (IOException ignored) {
        }
        if (statusCode == 404 && isDefaultOpenAiHost(baseUrl)) {
            return "OpenAI endpoint not found. Leave OPENAI_BASE_URL empty or set it to https://api.openai.com/v1.";
        }
        return "AI Review generation failed with status " + statusCode + ".";
    }

    private String summarizeBody(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String collapsed = value.trim().replaceAll("\\s+", " ");
        if (collapsed.length() <= 300) {
            return collapsed;
        }
        return collapsed.substring(0, 297) + "...";
    }

    private String normalizeBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return DEFAULT_OPENAI_BASE_URL;
        }
        String trimmed = value.trim();
        String normalized = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;

        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath().trim();
            if ("api.openai.com".equalsIgnoreCase(host) && (path.isEmpty() || "/".equals(path))) {
                return normalized + "/v1";
            }
        } catch (IllegalArgumentException ignored) {
        }

        return normalized;
    }

    private boolean isDefaultOpenAiHost(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }

        try {
            URI uri = URI.create(value);
            return "api.openai.com".equalsIgnoreCase(uri.getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String formatBoolean(Boolean value) {
        if (value == null) {
            return "not set";
        }
        return value ? "yes" : "no";
    }

    private String joinMistakeNames(List<MistakeTag> mistakes) {
        if (mistakes == null || mistakes.isEmpty()) {
            return "-";
        }
        return mistakes.stream()
                .map(MistakeTag::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(", "));
    }

    private String orDash(String value) {
        return StringUtils.hasText(value) ? value.trim() : "-";
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record AiReviewView(
            String state,
            String summary,
            List<String> strengths,
            List<String> weaknesses,
            List<String> improvements,
            List<String> suggestedMistakeTags,
            String confidence,
            String model,
            LocalDateTime generatedAt,
            String errorMessage,
            Integer processScore
    ) {
        public static AiReviewView empty() {
            return new AiReviewView(
                    "empty",
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    private record RawAiReview(
            String summary,
            List<String> strengths,
            List<String> weaknesses,
            List<String> improvements,
            List<String> suggestedMistakeTags,
            String confidence,
            Integer processScore
    ) {
    }
}
