package com.tradejournal.ai.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class TradingViewChartImportService {

    private static final Logger log = LoggerFactory.getLogger(TradingViewChartImportService.class);
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final int MAX_IMAGE_COUNT = 5;
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_TOTAL_IMAGE_SIZE_BYTES = 25L * 1024L * 1024L;
    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/gif"
    );
    private static final Pattern THOUSANDS_GROUPS_WITH_COMMA = Pattern.compile("^-?\\d{1,3}(,\\d{3})+(\\.\\d+)?$");
    private static final Pattern THOUSANDS_GROUPS_WITH_DOT = Pattern.compile("^-?\\d{1,3}(\\.\\d{3})+(,\\d+)?$");

    private static final String ANALYSIS_PROMPT = """
            You are an AI assistant specialized in analyzing TradingView chart screenshots to reconstruct a trade journal entry.

            The user uploads multiple screenshots of the same trade. These screenshots may include:
            1. HTF chart (higher timeframe context)
            2. LTF chart (execution chart)
            3. Result chart (after trade played out)

            Your task is to extract trade parameters and infer the setup as accurately as possible.

            IMPORTANT:
            The screenshots may contain many numbers such as:
            - OHLC values
            - cursor price
            - current market price
            - Fibonacci labels
            - annotations
            - trade tool labels

            You MUST only extract entry, stop loss, and take profit from the TradingView trade position tool (the risk/reward box), NOT from cursor values or header prices.

            IMAGE ROLE IDENTIFICATION
            Determine the role of each image:

            HTF image:
            - wider context
            - larger timeframe (M15, H1, H4 and similar)
            - shows bias and structure

            LTF image:
            - smaller timeframe (M1, M3, M5 and similar)
            - contains execution
            - trade box clearly visible

            Result image:
            - shows how trade evolved
            - may show TP hit or exit area
            - useful to estimate holding duration

            TRADE BOX EXTRACTION RULES
            If a TradingView position tool (long or short position box) is visible:

            SELL trade layout:
            - top boundary = stop loss
            - middle boundary = entry
            - bottom boundary = take profit

            BUY trade layout:
            - bottom boundary = stop loss
            - middle boundary = entry
            - top boundary = take profit

            You must extract the price labels that appear at the right edge of these boundaries.

            DO NOT use:
            - current price label
            - cursor price
            - OHLC header values
            - random annotations

            If multiple numbers exist, prioritize the price that is visually attached to the boundary of the trade box.

            FIELD EXTRACTION
            Extract the following fields if visible:
            - symbol
            - direction (BUY or SELL)
            - entryPrice
            - stopLoss
            - takeProfit
            - timeframeHTF
            - timeframeLTF
            - timeframeResult

            SETUP ANALYSIS
            Analyze the charts and infer:
            - htfBias
            - htfStructure
            - ltfTrigger
            - setupGuess
            - tradeIdea

            Common ICT-style concepts may include:
            - order block
            - fair value gap (FVG)
            - liquidity sweep
            - market structure shift (MSS)
            - premium / discount zones
            - London low / session liquidity

            Use chart annotations if visible.

            ESTIMATED METRICS
            If exact timestamps are not visible:
            - use the result screenshot first to estimate holding duration
            - only fall back to the LTF chart if the result screenshot is missing or unclear

            Estimate:
            - estimatedResultCandlesHeld
            - estimatedHoldingMinutes

            Method:
            1. Identify the approximate entry candle.
            2. Identify the approximate exit, TP hit, or SL hit candle on the result chart.
            3. Count visible candles on the result chart between them.
            4. Multiply by the result chart timeframe.

            These values must remain estimates. If you cannot estimate them reliably, return null.

            RESULT INFERENCE
            If a result screenshot clearly shows outcome:
            - if TP is hit: result = WIN, exitPrice = takeProfit, exitReason = TP_HIT
            - if SL is hit: result = LOSS, exitPrice = stopLoss, exitReason = SL_HIT
            - if annotations imply break-even: result = BREAKEVEN, exitPrice near entry when justified, exitReason = BE_HIT
            - if annotations imply partial take profit then break-even: result = PARTIAL_WIN, exitReason = PARTIAL_TP_THEN_BE

            If outcome is not clear enough, use null instead of guessing.

            SESSION INFERENCE
            If the chart shows time axis or references such as London, New York, Asia, or session liquidity, estimate:
            - sessionGuess
            - sessionConfidence (low, medium, or high)

            CONFIDENCE
            Provide a global confidence score based on:
            - clarity of trade box
            - clarity of price labels
            - clarity of timeframe
            - clarity of annotations

            Return JSON only with:
            - symbol
            - direction
            - timeframeHTF
            - timeframeLTF
            - timeframeResult
            - entryPrice
            - stopLoss
            - takeProfit
            - entrySource
            - stopLossSource
            - takeProfitSource
            - estimatedResultCandlesHeld
            - estimatedHoldingMinutes
            - result
            - exitPrice
            - exitReason
            - sessionGuess
            - sessionConfidence
            - htfBias
            - htfStructure
            - ltfTrigger
            - setupGuess
            - tradeIdea
            - confidence

            Precision of entry, stop loss, and take profit from the trade box is more important than guessing narrative details.
            Never invent prices that are not visible on the chart.
            Preserve full visible price magnitude and decimals. For example, if the label shows 74,216.6 then return "74,216.6".
            If any field cannot be determined, return null instead of guessing randomly.
            """;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;

    public TradingViewChartImportService(
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.trade-chart-model:gpt-4.1}") String model,
            @Value("${openai.trade-chart-timeout-seconds:60}") long timeoutSeconds
    ) {
        String configuredBaseUrl = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "";
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = StringUtils.hasText(model) ? model.trim() : "gpt-4.1";
        this.timeout = Duration.ofSeconds(Math.max(10L, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        if (StringUtils.hasText(configuredBaseUrl) && !normalizeComparableUrl(configuredBaseUrl).equals(this.baseUrl)) {
            log.info("Normalized OPENAI_BASE_URL from '{}' to '{}'.", configuredBaseUrl, this.baseUrl);
        }

        if (isConfigured()) {
            log.info("TradingView screenshot import configured. baseUrl='{}', model='{}', timeoutSeconds={}.",
                    this.baseUrl, this.model, this.timeout.toSeconds());
        } else {
            log.warn("TradingView screenshot import is disabled because OPENAI_API_KEY is not configured. baseUrl='{}', model='{}'.",
                    this.baseUrl, this.model);
        }
    }

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }

    public TradeChartAnalysis analyzeChart(MultipartFile file) {
        if (file == null) {
            return analyzeCharts(List.of());
        }
        return analyzeCharts(List.of(file));
    }

    public TradeChartAnalysis analyzeCharts(List<MultipartFile> files) {
        validateConfiguration();
        List<MultipartFile> normalizedFiles = normalizeFiles(files);
        validateImages(normalizedFiles);

        List<String> dataUrls = toDataUrls(normalizedFiles);
        String requestBody = buildRequestBody(dataUrls);
        URI endpointUri = URI.create(baseUrl + "/responses");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpointUri)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            log.info("Submitting TradingView screenshots to OpenAI. endpoint='{}', model='{}', imageCount={}, files='{}', totalSizeBytes={}.",
                    endpointUri, model, normalizedFiles.size(), summarizeFiles(normalizedFiles), totalSizeBytes(normalizedFiles));

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                String errorMessage = resolveOpenAiErrorMessage(response.body(), response.statusCode());
                log.warn("OpenAI TradingView screenshot analysis failed. endpoint='{}', model='{}', imageCount={}, status={}, message='{}', bodySnippet='{}'.",
                        endpointUri, model, normalizedFiles.size(), response.statusCode(), errorMessage, summarizeBody(response.body()));
                throw new RuntimeException(errorMessage);
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            String outputJson = extractOutputJson(responseJson);
            if (!StringUtils.hasText(outputJson)) {
                log.warn("OpenAI TradingView screenshot analysis returned no output payload. endpoint='{}', model='{}', imageCount={}, status={}.",
                        endpointUri, model, normalizedFiles.size(), response.statusCode());
                throw new RuntimeException("OpenAI did not return a chart analysis payload.");
            }

            RawTradeChartAnalysis analysis = parseAnalysis(outputJson);
            TradeChartAnalysis normalizedAnalysis = normalizeAnalysis(analysis);
            log.info("OpenAI TradingView screenshot analysis succeeded. endpoint='{}', model='{}', imageCount={}, symbol='{}', direction='{}', timeframeHTF='{}', timeframeLTF='{}'.",
                    endpointUri, model, normalizedFiles.size(), normalizedAnalysis.symbol(), normalizedAnalysis.direction(),
                    normalizedAnalysis.timeframeHTF(), normalizedAnalysis.timeframeLTF());
            return normalizedAnalysis;
        } catch (IOException ex) {
            log.error("Unable to read TradingView screenshot analysis response. endpoint='{}', model='{}'.",
                    endpointUri, model, ex);
            throw new RuntimeException("Unable to read the TradingView screenshot import response.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("TradingView screenshot analysis was interrupted. endpoint='{}', model='{}'.",
                    endpointUri, model, ex);
            throw new RuntimeException("TradingView screenshot import was interrupted.", ex);
        }
    }

    private void validateConfiguration() {
        if (!isConfigured()) {
            throw new IllegalStateException("TradingView screenshot import is not configured. Set OPENAI_API_KEY first.");
        }
    }

    public int getMaxImageCount() {
        return MAX_IMAGE_COUNT;
    }

    private List<MultipartFile> normalizeFiles(List<MultipartFile> files) {
        List<MultipartFile> normalized = new ArrayList<>();
        if (files == null) {
            return normalized;
        }

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            normalized.add(file);
        }
        return normalized;
    }

    private void validateImages(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Choose between 1 and 5 TradingView screenshots to analyze.");
        }

        if (files.size() > MAX_IMAGE_COUNT) {
            throw new IllegalArgumentException("Choose up to 5 TradingView screenshots at a time.");
        }

        long totalSizeBytes = 0L;
        for (MultipartFile file : files) {
            totalSizeBytes += validateSingleImage(file);
        }

        if (totalSizeBytes > MAX_TOTAL_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("Selected TradingView screenshots are too large together. Keep the total upload size at 25MB or below.");
        }
    }

    private long validateSingleImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose between 1 and 5 TradingView screenshots to analyze.");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (!ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Only PNG, JPG, WEBP, or GIF screenshots are supported for TradingView screenshot import.");
        }

        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("Each TradingView screenshot must be 10MB or smaller.");
        }

        return file.getSize();
    }

    private List<String> toDataUrls(List<MultipartFile> files) {
        List<String> dataUrls = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                String contentType = normalizeContentType(file.getContentType());
                if (!ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType)) {
                    throw new IllegalArgumentException("Only PNG, JPG, WEBP, or GIF screenshots are supported for TradingView screenshot import.");
                }
                String encoded = Base64.getEncoder().encodeToString(file.getBytes());
                dataUrls.add("data:" + contentType + ";base64," + encoded);
            } catch (IOException ex) {
                throw new IllegalArgumentException("One of the TradingView screenshots could not be processed.");
            }
        }
        return dataUrls;
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf(';');
        return separatorIndex >= 0 ? normalized.substring(0, separatorIndex).trim() : normalized;
    }

    private String buildRequestBody(List<String> dataUrls) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("max_output_tokens", 700);

        ArrayNode input = payload.putArray("input");

        ObjectNode systemMessage = input.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", ANALYSIS_PROMPT);

        ObjectNode userMessage = input.addObject();
        userMessage.put("role", "user");
        ArrayNode userContent = userMessage.putArray("content");

        ObjectNode instructionPart = userContent.addObject();
        instructionPart.put("type", "input_text");
        instructionPart.put("text", "Analyze these TradingView screenshots as one trade. First identify which image is HTF, LTF, or result. Extract entry, stop loss, and take profit only from the right-edge labels attached to the TradingView position tool boundaries when visible. Return one combined JSON object.");

        for (String dataUrl : dataUrls) {
            ObjectNode imagePart = userContent.addObject();
            imagePart.put("type", "input_image");
            imagePart.put("image_url", dataUrl);
            imagePart.put("detail", "high");
        }

        ObjectNode text = payload.putObject("text");
        ObjectNode format = text.putObject("format");
        format.put("type", "json_schema");
        format.put("name", "trading_chart_analysis");
        format.put("strict", true);
        format.set("schema", buildAnalysisSchema());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to prepare the TradingView screenshot analysis request.", ex);
        }
    }

    private ObjectNode buildAnalysisSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        addNullableString(properties, "symbol",
                "Detected trading symbol from the screenshots.");
        addNullableEnum(properties, "direction",
                "Trade direction. Use BUY, SELL, or null if not visible.",
                "BUY", "SELL");

        addNullablePriceText(properties, "entryPrice",
                "Exact visible entry price label copied from the TradingView position tool boundary on the right edge. Preserve full digits and separators, for example 73,388.3.");
        addNullablePriceText(properties, "stopLoss",
                "Exact visible stop loss price label copied from the TradingView position tool boundary on the right edge. Preserve full digits and separators, for example 74,216.6.");
        addNullablePriceText(properties, "takeProfit",
                "Exact visible take profit price label copied from the TradingView position tool boundary on the right edge. Preserve full digits and separators, for example 70,874.5.");

        addNullableString(properties, "timeframeHTF",
                "Detected higher timeframe chart label, such as H4, H1, M30, or M15.");
        addNullableString(properties, "timeframeLTF",
                "Detected lower timeframe execution chart label, such as M15, M5, M3, or M1.");
        addNullableString(properties, "timeframeResult",
                "Detected timeframe label on the result or outcome screenshot, such as M15, M5, M3, or H1.");
        addNullableString(properties, "entrySource",
                "Short explanation of where the entry price came from, ideally referring to the trade box middle boundary right-edge label.");
        addNullableString(properties, "stopLossSource",
                "Short explanation of where the stop loss came from, ideally referring to the trade box top or bottom boundary right-edge label.");
        addNullableString(properties, "takeProfitSource",
                "Short explanation of where the take profit came from, ideally referring to the trade box top or bottom boundary right-edge label.");
        addNullableInteger(properties, "estimatedResultCandlesHeld",
                "Estimated number of candles held on the result screenshot timeframe.");
        addNullableInteger(properties, "estimatedHoldingMinutes",
                "Estimated total holding time in minutes, preferably recalculated from the result screenshot timeframe.");
        addNullableEnum(properties, "result",
                "Inferred trade result from the result screenshot.",
                "WIN", "LOSS", "BREAKEVEN", "PARTIAL_WIN", "UNKNOWN");
        addNullablePriceText(properties, "exitPrice",
                "Exit price inferred from the result screenshot. Prefer take profit, stop loss, or breakeven level only when outcome is clear.");
        addNullableEnum(properties, "exitReason",
                "Why the trade exited or ended.",
                "TP_HIT", "SL_HIT", "BE_HIT", "PARTIAL_TP_THEN_BE", "MANUAL_EXIT", "UNKNOWN");
        addNullableString(properties, "sessionGuess",
                "Estimated trading session, such as Asia, London, New York, or Other.");
        addNullableEnum(properties, "sessionConfidence",
                "Confidence of the session estimate.",
                "low", "medium", "high");
        addNullableString(properties, "htfBias",
                "Higher timeframe directional bias.");
        addNullableString(properties, "htfStructure",
                "Higher timeframe structure observation, such as bearish BOS, premium OB rejection, or range high sweep.");
        addNullableString(properties, "ltfTrigger",
                "Lower timeframe execution trigger or confirmation.");
        addNullableString(properties, "setupGuess",
                "Best guess of the visible trading setup.");
        addNullableString(properties, "tradeIdea",
                "Short one-sentence trade idea summary.");
        addNullableEnum(properties, "confidence",
                "Global confidence label based on clarity of the charts.",
                "low", "medium", "high");

        ArrayNode required = schema.putArray("required");
        required.add("symbol");
        required.add("direction");
        required.add("timeframeHTF");
        required.add("timeframeLTF");
        required.add("timeframeResult");
        required.add("entryPrice");
        required.add("stopLoss");
        required.add("takeProfit");
        required.add("entrySource");
        required.add("stopLossSource");
        required.add("takeProfitSource");
        required.add("estimatedResultCandlesHeld");
        required.add("estimatedHoldingMinutes");
        required.add("result");
        required.add("exitPrice");
        required.add("exitReason");
        required.add("sessionGuess");
        required.add("sessionConfidence");
        required.add("htfBias");
        required.add("htfStructure");
        required.add("ltfTrigger");
        required.add("setupGuess");
        required.add("tradeIdea");
        required.add("confidence");

        schema.put("additionalProperties", false);
        return schema;
    }

    private void addNullablePriceText(ObjectNode properties, String fieldName, String description) {
        ObjectNode field = properties.putObject(fieldName);
        ArrayNode fieldType = field.putArray("type");
        fieldType.add("string");
        fieldType.add("null");
        field.put("description", description);
    }

    private void addNullableString(ObjectNode properties, String fieldName, String description) {
        ObjectNode field = properties.putObject(fieldName);
        ArrayNode fieldType = field.putArray("type");
        fieldType.add("string");
        fieldType.add("null");
        field.put("description", description);
    }

    private void addNullableInteger(ObjectNode properties, String fieldName, String description) {
        ObjectNode field = properties.putObject(fieldName);
        ArrayNode fieldType = field.putArray("type");
        fieldType.add("integer");
        fieldType.add("null");
        field.put("description", description);
    }

    private void addNullableEnum(ObjectNode properties, String fieldName, String description, String... values) {
        ObjectNode field = properties.putObject(fieldName);
        ArrayNode fieldType = field.putArray("type");
        fieldType.add("string");
        fieldType.add("null");
        ArrayNode enumValues = field.putArray("enum");
        enumValues.addNull();
        for (String value : values) {
            enumValues.add(value);
        }
        field.put("description", description);
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
        return "TradingView screenshot analysis failed with status " + statusCode + ".";
    }

    private String extractOutputJson(JsonNode responseJson) {
        String directOutput = responseJson.path("output_text").asText("");
        if (StringUtils.hasText(directOutput)) {
            return stripMarkdownFence(directOutput.trim());
        }

        JsonNode output = responseJson.path("output");
        if (output.isArray()) {
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
        }

        return "";
    }

    private RawTradeChartAnalysis parseAnalysis(String outputJson) throws JsonProcessingException {
        return objectMapper.readValue(outputJson, RawTradeChartAnalysis.class);
    }

    private TradeChartAnalysis normalizeAnalysis(RawTradeChartAnalysis analysis) {
        if (analysis == null) {
            return new TradeChartAnalysis(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        return new TradeChartAnalysis(
                normalizeSymbol(analysis.symbol()),
                normalizeDirection(analysis.direction()),
                normalizeTimeframe(analysis.timeframeHTF()),
                normalizeTimeframe(analysis.timeframeLTF()),
                normalizeTimeframe(analysis.timeframeResult()),
                parseVisiblePrice(analysis.entryPrice()),
                parseVisiblePrice(analysis.stopLoss()),
                parseVisiblePrice(analysis.takeProfit()),
                normalizeText(analysis.entrySource()),
                normalizeText(analysis.stopLossSource()),
                normalizeText(analysis.takeProfitSource()),
                normalizeNonNegativeInteger(analysis.estimatedResultCandlesHeld()),
                normalizeNonNegativeInteger(analysis.estimatedHoldingMinutes()),
                normalizeResult(analysis.result()),
                parseVisiblePrice(analysis.exitPrice()),
                normalizeExitReason(analysis.exitReason()),
                normalizeSessionGuess(analysis.sessionGuess()),
                normalizeConfidenceLabel(analysis.sessionConfidence()),
                normalizeText(analysis.htfBias()),
                normalizeText(analysis.htfStructure()),
                normalizeText(analysis.ltfTrigger()),
                normalizeText(analysis.setupGuess()),
                normalizeText(analysis.tradeIdea()),
                null,
                null,
                null,
                null,
                null,
                normalizeConfidenceLabel(analysis.confidence())
        );
    }

    public static Double parseVisiblePrice(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim()
                .replace("\u00A0", "")
                .replace(" ", "")
                .replaceAll("[^0-9,.-]", "");

        if (!StringUtils.hasText(normalized)) {
            return null;
        }

        if (normalized.indexOf(',') >= 0 && normalized.indexOf('.') >= 0) {
            int lastComma = normalized.lastIndexOf(',');
            int lastDot = normalized.lastIndexOf('.');
            if (lastDot > lastComma) {
                normalized = normalized.replace(",", "");
            } else {
                normalized = normalized.replace(".", "").replace(',', '.');
            }
        } else if (normalized.indexOf(',') >= 0) {
            if (THOUSANDS_GROUPS_WITH_COMMA.matcher(normalized).matches()) {
                normalized = normalized.replace(",", "");
            } else {
                normalized = normalized.replace(',', '.');
            }
        } else if (normalized.indexOf('.') >= 0 && THOUSANDS_GROUPS_WITH_DOT.matcher(normalized).matches()) {
            normalized = normalized.replace(".", "");
        }

        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeSymbol(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private String normalizeDirection(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("BUY".equals(normalized)) {
            return "BUY";
        }
        if ("SELL".equals(normalized)) {
            return "SELL";
        }
        return null;
    }

    private String normalizeTimeframe(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace("MINUTES", "M")
                .replace("MINUTE", "M")
                .replace("MINS", "M")
                .replace("MIN", "M")
                .replace("HOURS", "H")
                .replace("HOUR", "H")
                .replace("HR", "H")
                .replace(" ", "");

        return switch (normalized) {
            case "1M", "M1" -> "M1";
            case "3M", "M3" -> "M3";
            case "5M", "M5" -> "M5";
            case "15M", "M15" -> "M15";
            case "30M", "M30" -> "M30";
            case "60M", "1H", "H1" -> "H1";
            case "240M", "4H", "H4" -> "H4";
            default -> normalized;
        };
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeConfidenceLabel(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "medium", "high" -> normalized;
            default -> value.trim();
        };
    }

    private String normalizeSessionGuess(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ');

        if (normalized.contains("new york") || normalized.equals("ny")) {
            return "New York";
        }
        if (normalized.contains("london")) {
            return "London";
        }
        if (normalized.contains("asia") || normalized.contains("tokyo") || normalized.contains("sydney")) {
            return "Asia";
        }
        if (normalized.contains("other")) {
            return "Other";
        }
        return value.trim();
    }

    private String normalizeResult(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
        return switch (normalized) {
            case "WIN", "LOSS", "BREAKEVEN", "PARTIAL_WIN", "UNKNOWN" -> normalized;
            case "BE" -> "BREAKEVEN";
            case "PARTIAL", "PARTIALTP", "PARTIAL_PROFIT" -> "PARTIAL_WIN";
            default -> null;
        };
    }

    private String normalizeExitReason(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
        return switch (normalized) {
            case "TP_HIT", "SL_HIT", "BE_HIT", "PARTIAL_TP_THEN_BE", "MANUAL_EXIT", "UNKNOWN" -> normalized;
            default -> null;
        };
    }

    private Integer normalizeNonNegativeInteger(Integer value) {
        if (value == null || value < 0) {
            return null;
        }
        return value;
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

    private String normalizeComparableUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
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

    private String safeFilename(MultipartFile file) {
        if (file == null || !StringUtils.hasText(file.getOriginalFilename())) {
            return "(unnamed)";
        }
        return file.getOriginalFilename().trim();
    }

    private String safeContentType(MultipartFile file) {
        if (file == null || !StringUtils.hasText(file.getContentType())) {
            return "(unknown)";
        }
        return file.getContentType().trim();
    }

    private long totalSizeBytes(List<MultipartFile> files) {
        long total = 0L;
        if (files == null) {
            return total;
        }

        for (MultipartFile file : files) {
            if (file != null) {
                total += file.getSize();
            }
        }
        return total;
    }

    private String summarizeFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }

        List<String> names = new ArrayList<>();
        for (MultipartFile file : files) {
            names.add(safeFilename(file));
            if (names.size() == 5) {
                break;
            }
        }
        return String.join(", ", names);
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

    public record TradeChartAnalysis(
            String symbol,
            String direction,
            String timeframeHTF,
            String timeframeLTF,
            String timeframeResult,
            Double entryPrice,
            Double stopLoss,
            Double takeProfit,
            String entrySource,
            String stopLossSource,
            String takeProfitSource,
            Integer estimatedResultCandlesHeld,
            Integer estimatedHoldingMinutes,
            String result,
            Double exitPrice,
            String exitReason,
            String sessionGuess,
            String sessionConfidence,
            String htfBias,
            String htfStructure,
            String ltfTrigger,
            String setupGuess,
            String tradeIdea,
            String matchedSetupId,
            String matchedSetupName,
            String matchedSetupConfidence,
            String newSetupSuggestedName,
            String newSetupSuggestedDescription,
            String confidence
    ) {
        public TradeChartAnalysis withHoldingTime(String resolvedTimeframeResult, Integer resultCandlesHeld, Integer holdingMinutes) {
            return new TradeChartAnalysis(
                    symbol, direction, timeframeHTF, timeframeLTF, resolvedTimeframeResult,
                    entryPrice, stopLoss, takeProfit,
                    entrySource, stopLossSource, takeProfitSource,
                    resultCandlesHeld, holdingMinutes,
                    result, exitPrice, exitReason,
                    sessionGuess, sessionConfidence,
                    htfBias, htfStructure, ltfTrigger,
                    setupGuess, tradeIdea,
                    matchedSetupId, matchedSetupName, matchedSetupConfidence,
                    newSetupSuggestedName, newSetupSuggestedDescription,
                    confidence
            );
        }

        public TradeChartAnalysis withResolvedOutcome(String resolvedResult, Double resolvedExitPrice, String resolvedExitReason) {
            return new TradeChartAnalysis(
                    symbol, direction, timeframeHTF, timeframeLTF, timeframeResult,
                    entryPrice, stopLoss, takeProfit,
                    entrySource, stopLossSource, takeProfitSource,
                    estimatedResultCandlesHeld, estimatedHoldingMinutes,
                    resolvedResult, resolvedExitPrice, resolvedExitReason,
                    sessionGuess, sessionConfidence,
                    htfBias, htfStructure, ltfTrigger,
                    setupGuess, tradeIdea,
                    matchedSetupId, matchedSetupName, matchedSetupConfidence,
                    newSetupSuggestedName, newSetupSuggestedDescription,
                    confidence
            );
        }

        public TradeChartAnalysis withSetupResolution(
                String resolvedMatchedSetupId,
                String resolvedMatchedSetupName,
                String resolvedMatchedSetupConfidence,
                String resolvedSuggestedName,
                String resolvedSuggestedDescription
        ) {
            return new TradeChartAnalysis(
                    symbol, direction, timeframeHTF, timeframeLTF, timeframeResult,
                    entryPrice, stopLoss, takeProfit,
                    entrySource, stopLossSource, takeProfitSource,
                    estimatedResultCandlesHeld, estimatedHoldingMinutes,
                    result, exitPrice, exitReason,
                    sessionGuess, sessionConfidence,
                    htfBias, htfStructure, ltfTrigger,
                    setupGuess, tradeIdea,
                    resolvedMatchedSetupId, resolvedMatchedSetupName, resolvedMatchedSetupConfidence,
                    resolvedSuggestedName, resolvedSuggestedDescription,
                    confidence
            );
        }
    }

    private record RawTradeChartAnalysis(
            String symbol,
            String direction,
            String timeframeHTF,
            String timeframeLTF,
            String timeframeResult,
            String entryPrice,
            String stopLoss,
            String takeProfit,
            String entrySource,
            String stopLossSource,
            String takeProfitSource,
            Integer estimatedResultCandlesHeld,
            Integer estimatedHoldingMinutes,
            String result,
            String exitPrice,
            String exitReason,
            String sessionGuess,
            String sessionConfidence,
            String htfBias,
            String htfStructure,
            String ltfTrigger,
            String setupGuess,
            String tradeIdea,
            String confidence
    ) {
    }
}
