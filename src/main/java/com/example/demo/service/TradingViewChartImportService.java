package com.example.demo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class TradingViewChartImportService {

    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final Pattern THOUSANDS_GROUPS_WITH_COMMA = Pattern.compile("^-?\\d{1,3}(,\\d{3})+(\\.\\d+)?$");
    private static final Pattern THOUSANDS_GROUPS_WITH_DOT = Pattern.compile("^-?\\d{1,3}(\\.\\d{3})+(,\\d+)?$");

    private static final String ANALYSIS_PROMPT = """
            You are a trading chart analyzer.

            Analyze this trading chart screenshot from TradingView.

            Focus on the trade overlay or risk-reward box first.
            For prices, prioritize the three labels attached to that box on the right price axis:
            - entry is usually the dark or gray label
            - stop loss is usually the red or pink label
            - take profit is usually the green or teal label

            Ignore the bid and ask quote boxes in the top-left when trade prices are visible on the chart.
            Ignore the OHLC row at the top unless the trade overlay prices are missing.

            Extract the following information if visible:

            - trading symbol
            - trade direction (buy or sell)
            - entry price
            - stop loss
            - take profit
            - timeframe
            - possible trading setup (FVG, Order Block, Break of Structure, etc)

            Return ONLY valid JSON.

            Example format:

            {
             "symbol": "",
             "direction": "",
             "entryPrice": "73,388.3",
             "stopLoss": "74,216.6",
             "takeProfit": "70,874.5",
             "timeframe": "",
             "setupGuess": ""
            }

            For entryPrice, stopLoss, and takeProfit:
            - copy the exact visible label text when possible
            - preserve the full magnitude and decimal digits
            - do not abbreviate, round, or drop thousands
            - if the screenshot shows 74,216.6 then return "74,216.6"
            - if the screenshot shows 73,388.3 then return "73,388.3"
            - if the screenshot shows 70,874.5 then return "70,874.5"

            If a price value is not clearly visible, return null.
            If a text value is not clearly visible, return an empty string.
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
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = StringUtils.hasText(model) ? model.trim() : "gpt-4.1";
        this.timeout = Duration.ofSeconds(Math.max(10L, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }

    public TradeChartAnalysis analyzeChart(MultipartFile file) {
        validateConfiguration();
        validateImage(file);

        String dataUrl = toDataUrl(file);
        String requestBody = buildRequestBody(dataUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/responses"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new RuntimeException(resolveOpenAiErrorMessage(response.body(), response.statusCode()));
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            String outputJson = extractOutputJson(responseJson);
            if (!StringUtils.hasText(outputJson)) {
                throw new RuntimeException("OpenAI did not return a chart analysis payload.");
            }

            RawTradeChartAnalysis analysis = parseAnalysis(outputJson);
            return normalizeAnalysis(analysis);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to read the TradingView screenshot import response.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TradingView screenshot import was interrupted.", ex);
        }
    }

    private void validateConfiguration() {
        if (!isConfigured()) {
            throw new IllegalStateException("TradingView screenshot import is not configured. Set OPENAI_API_KEY first.");
        }
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a TradingView screenshot to analyze.");
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are supported for TradingView screenshot import.");
        }

        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("TradingView screenshot must be 10MB or smaller.");
        }
    }

    private String toDataUrl(MultipartFile file) {
        try {
            String contentType = file.getContentType() == null ? "image/png" : file.getContentType().trim().toLowerCase(Locale.ROOT);
            String encoded = Base64.getEncoder().encodeToString(file.getBytes());
            return "data:" + contentType + ";base64," + encoded;
        } catch (IOException ex) {
            throw new IllegalArgumentException("TradingView screenshot could not be processed.");
        }
    }

    private String buildRequestBody(String dataUrl) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("max_output_tokens", 300);

        ArrayNode input = payload.putArray("input");

        ObjectNode systemMessage = input.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", ANALYSIS_PROMPT);

        ObjectNode userMessage = input.addObject();
        userMessage.put("role", "user");
        ArrayNode userContent = userMessage.putArray("content");

        ObjectNode instructionPart = userContent.addObject();
        instructionPart.put("type", "input_text");
        instructionPart.put("text", "Analyze this TradingView chart screenshot and return the requested JSON object.");

        ObjectNode imagePart = userContent.addObject();
        imagePart.put("type", "input_image");
        imagePart.put("image_url", dataUrl);
        imagePart.put("detail", "high");

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

        ObjectNode symbol = properties.putObject("symbol");
        symbol.put("type", "string");
        symbol.put("description", "Detected trading symbol from the chart screenshot.");

        ObjectNode direction = properties.putObject("direction");
        direction.put("type", "string");
        ArrayNode directionEnum = direction.putArray("enum");
        directionEnum.add("");
        directionEnum.add("BUY");
        directionEnum.add("SELL");
        direction.put("description", "Trade direction. Use BUY, SELL, or an empty string if not visible.");

        addNullablePriceText(properties, "entryPrice",
                "Exact visible entry price label copied from the chart. Preserve full digits and separators, for example 73,388.3.");
        addNullablePriceText(properties, "stopLoss",
                "Exact visible stop loss price label copied from the chart. Preserve full digits and separators, for example 74,216.6.");
        addNullablePriceText(properties, "takeProfit",
                "Exact visible take profit price label copied from the chart. Preserve full digits and separators, for example 70,874.5.");

        ObjectNode timeframe = properties.putObject("timeframe");
        timeframe.put("type", "string");
        timeframe.put("description", "Detected chart timeframe, such as M15 or H1.");

        ObjectNode setupGuess = properties.putObject("setupGuess");
        setupGuess.put("type", "string");
        setupGuess.put("description", "Best guess of the visible trading setup.");

        ArrayNode required = schema.putArray("required");
        required.add("symbol");
        required.add("direction");
        required.add("entryPrice");
        required.add("stopLoss");
        required.add("takeProfit");
        required.add("timeframe");
        required.add("setupGuess");

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

    private String resolveOpenAiErrorMessage(String responseBody, int statusCode) {
        try {
            JsonNode errorBody = objectMapper.readTree(responseBody);
            String message = errorBody.path("error").path("message").asText("");
            if (StringUtils.hasText(message)) {
                return message;
            }
        } catch (IOException ignored) {
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
            return new TradeChartAnalysis("", "", null, null, null, "", "");
        }

        return new TradeChartAnalysis(
                normalizeSymbol(analysis.symbol()),
                normalizeDirection(analysis.direction()),
                parseVisiblePrice(analysis.entryPrice()),
                parseVisiblePrice(analysis.stopLoss()),
                parseVisiblePrice(analysis.takeProfit()),
                normalizeTimeframe(analysis.timeframe()),
                normalizeText(analysis.setupGuess())
        );
    }

    static Double parseVisiblePrice(String value) {
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
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private String normalizeDirection(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("BUY".equals(normalized)) {
            return "BUY";
        }
        if ("SELL".equals(normalized)) {
            return "SELL";
        }
        return "";
    }

    private String normalizeTimeframe(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
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
        return StringUtils.hasText(value) ? value.trim() : "";
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

    private String stripTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "https://api.openai.com/v1";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public record TradeChartAnalysis(
            String symbol,
            String direction,
            Double entryPrice,
            Double stopLoss,
            Double takeProfit,
            String timeframe,
            String setupGuess
    ) {
    }

    private record RawTradeChartAnalysis(
            String symbol,
            String direction,
            String entryPrice,
            String stopLoss,
            String takeProfit,
            String timeframe,
            String setupGuess
    ) {
    }
}
