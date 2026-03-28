package com.tradejournal.trade.service;

import com.tradejournal.auth.service.UserService;
import com.tradejournal.setup.domain.Setup;
import com.tradejournal.trade.domain.Trade;
import com.tradejournal.auth.domain.User;
import com.tradejournal.setup.repository.SetupRepository;
import com.tradejournal.trade.repository.TradeRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.xml.XMLConstants;
import java.io.Serializable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Service
public class TradeImportService {

    private static final DateTimeFormatter MT5_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy.MM.dd HH:mm")
            .optionalStart()
            .appendPattern(":ss")
            .optionalEnd()
            .toFormatter(Locale.ENGLISH);
    private static final String XLSX_SHEET_ENTRY = "xl/worksheets/sheet1.xml";
    private static final String XLSX_SHARED_STRINGS_ENTRY = "xl/sharedStrings.xml";
    private static final Set<String> XLSX_ALLOWED_XML_ENTRIES = Set.of(XLSX_SHEET_ENTRY, XLSX_SHARED_STRINGS_ENTRY);
    private static final int MAX_XLSX_ENTRY_COUNT = 256;
    private static final int MAX_XLSX_ENTRY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_XLSX_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final int ZIP_BUFFER_SIZE = 8 * 1024;

    private final TradeRepository tradeRepository;
    private final SetupRepository setupRepository;
    private final UserService userService;
    private final TradeService tradeService;

    public TradeImportService(
            TradeRepository tradeRepository,
            SetupRepository setupRepository,
            UserService userService,
            TradeService tradeService
    ) {
        this.tradeRepository = tradeRepository;
        this.setupRepository = setupRepository;
        this.userService = userService;
        this.tradeService = tradeService;
    }

    @Transactional
    public ImportResult importWorkbook(MultipartFile file, User user, ImportOptions options) {
        return confirmImport(user, previewWorkbook(file, user, options));
    }

    public ImportPreview previewWorkbook(MultipartFile file, User user, ImportOptions options) {
        validateImportRequest(file, user);

        ImportOptions normalizedOptions = normalizeOptions(options);
        ParsedReport report = parseReport(file);
        if (report.positions().isEmpty()) {
            throw new IllegalArgumentException("No closed positions were found in the MT5 report.");
        }

        String accountLabel = normalizeOrDefault(report.accountLabel(), "MT5 Account");
        long existingTradeCount = tradeRepository.countByUserId(user.getId());
        int tradeLimit = userService.resolveTradeLimit(user);
        boolean proAccess = userService.hasProAccess(user);
        long remainingTradeSlots = proAccess ? 0 : Math.max(0, tradeLimit - existingTradeCount);

        List<ImportPreviewTrade> previewTrades = new ArrayList<>();
        int duplicateCount = 0;

        for (Mt5PositionRow row : report.positions()) {
            boolean duplicate = isDuplicate(user, row);
            if (duplicate) {
                duplicateCount++;
            }
            previewTrades.add(preparePreviewTrade(row, normalizedOptions.sessionMode(), duplicate));
        }

        int importableCount = Math.max(0, report.positions().size() - duplicateCount);
        boolean canImport = importableCount > 0
                && (proAccess || existingTradeCount + importableCount <= tradeLimit);
        String blockingMessage = null;
        if (importableCount == 0) {
            blockingMessage = "No new trades to import. Every closed position in this file is already in your journal.";
        } else if (!proAccess && existingTradeCount + importableCount > tradeLimit) {
            blockingMessage = "This preview contains " + importableCount
                    + " new trades but your free plan only has " + remainingTradeSlots
                    + " slot(s) left. Upgrade to Pro or import a smaller file.";
        }

        return new ImportPreview(
                UUID.randomUUID().toString(),
                user.getId(),
                normalizeOrDefault(file.getOriginalFilename(), "mt5-history.xlsx"),
                accountLabel,
                normalizedOptions.setupName(),
                normalizedOptions.defaultHtf(),
                normalizedOptions.defaultLtf(),
                normalizedOptions.sessionMode(),
                report.positions().size(),
                importableCount,
                duplicateCount,
                report.skippedRows(),
                proAccess,
                remainingTradeSlots,
                canImport,
                blockingMessage,
                previewTrades
        );
    }

    @Transactional
    public ImportResult confirmImport(User user, ImportPreview preview) {
        if (user == null) {
            throw new IllegalArgumentException("You must be logged in to import trades.");
        }
        if (preview == null) {
            throw new IllegalArgumentException("There is no MT5 import preview to confirm.");
        }
        if (!StringUtils.hasText(preview.userId()) || !preview.userId().equals(user.getId())) {
            throw new IllegalArgumentException("This MT5 import preview belongs to another account. Please upload the file again.");
        }

        String accountLabel = normalizeOrDefault(preview.accountLabel(), "MT5 Account");
        long existingTradeCount = tradeRepository.countByUserId(user.getId());

        List<ImportPreviewTrade> importablePreviewTrades = new ArrayList<>();
        int duplicateCount = 0;

        for (ImportPreviewTrade previewTrade : preview.previewTrades()) {
            if (isDuplicate(user, previewTrade)) {
                duplicateCount++;
                continue;
            }

            importablePreviewTrades.add(previewTrade);
        }

        int importableCount = importablePreviewTrades.size();
        if (importableCount == 0) {
            throw new IllegalStateException("There are no new trades left to import from this preview.");
        }

        int tradeLimit = userService.resolveTradeLimit(user);
        if (!userService.hasProAccess(user) && existingTradeCount + importableCount > tradeLimit) {
            long remaining = Math.max(0, tradeLimit - existingTradeCount);
            throw new IllegalStateException(
                    "This import would exceed your free plan limit. Remaining slots: " + remaining + ". Upgrade to Pro or import a smaller file.");
        }

        Setup setup = resolveOrCreateSetup(user, preview.setupName());
        List<Trade> tradesToSave = new ArrayList<>();
        for (ImportPreviewTrade previewTrade : importablePreviewTrades) {
            tradesToSave.add(buildTrade(
                    user,
                    setup,
                    accountLabel,
                    preview.defaultHtf(),
                    preview.defaultLtf(),
                    previewTrade
            ));
        }

        tradeRepository.saveAll(tradesToSave);
        tradeService.refreshRMultiplesForUser(user.getId());

        return new ImportResult(
                tradesToSave.size(),
                duplicateCount,
                preview.skippedCount(),
                accountLabel,
                setup.getName()
        );
    }

    private void validateImportRequest(MultipartFile file, User user) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose an MT5 history file to import.");
        }

        if (user == null) {
            throw new IllegalArgumentException("You must be logged in to import trades.");
        }
    }

    private ImportOptions normalizeOptions(ImportOptions options) {
        return new ImportOptions(
                normalizeOrDefault(options != null ? options.setupName() : null, "MT5 Import"),
                normalizeOrDefault(options != null ? options.defaultHtf() : null, "H1"),
                normalizeOrDefault(options != null ? options.defaultLtf() : null, "M5"),
                normalizeOrDefault(options != null ? options.sessionMode() : null, "AUTO")
        );
    }

    private ParsedReport parseReport(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && originalFilename.toLowerCase(Locale.ENGLISH).endsWith(".xlsx")) {
            return parseXlsxReport(file);
        }
        return parseWorkbookReport(file);
    }

    private ParsedReport parseWorkbookReport(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("The uploaded workbook does not contain any sheet.");
            }

            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
            String accountLabel = null;
            boolean readingPositions = false;
            int skippedRows = 0;
            List<Mt5PositionRow> positions = new ArrayList<>();

            for (Row row : sheet) {
                String firstCell = getCellText(row, 0, formatter);

                if (!StringUtils.hasText(accountLabel) && "Account:".equalsIgnoreCase(firstCell)) {
                    accountLabel = getCellText(row, 3, formatter);
                }

                if (!readingPositions) {
                    if (isPositionsHeaderRow(row, formatter)) {
                        readingPositions = true;
                    }
                    continue;
                }

                if ("Orders".equalsIgnoreCase(firstCell)) {
                    break;
                }

                if (isBlankDataRow(row, formatter)) {
                    continue;
                }

                Mt5PositionRow position = parsePositionRow(row, formatter);
                if (position == null) {
                    skippedRows++;
                    continue;
                }

                positions.add(position);
            }

            return new ParsedReport(accountLabel, positions, skippedRows);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read the MT5 workbook. Please upload a valid .xlsx or .xls export.");
        }
    }

    private ParsedReport parseXlsxReport(MultipartFile file) {
        try {
            Map<String, byte[]> entries = unzipEntries(file);
            byte[] sheetBytes = entries.get(XLSX_SHEET_ENTRY);
            if (sheetBytes == null) {
                throw new IllegalArgumentException("The uploaded MT5 workbook does not contain sheet1.xml.");
            }

            List<String> sharedStrings = parseSharedStrings(entries.get(XLSX_SHARED_STRINGS_ENTRY));
            List<Map<String, String>> rows = parseSheetRows(sheetBytes, sharedStrings);

            String accountLabel = null;
            boolean readingPositions = false;
            int skippedRows = 0;
            List<Mt5PositionRow> positions = new ArrayList<>();

            for (Map<String, String> row : rows) {
                String firstCell = row.getOrDefault("A", "");
                if (!StringUtils.hasText(accountLabel) && "Account:".equalsIgnoreCase(firstCell)) {
                    accountLabel = row.getOrDefault("D", "");
                }

                if (!readingPositions) {
                    if ("Time".equalsIgnoreCase(row.getOrDefault("A", ""))
                            && "Position".equalsIgnoreCase(row.getOrDefault("B", ""))
                            && "Symbol".equalsIgnoreCase(row.getOrDefault("C", ""))
                            && "Type".equalsIgnoreCase(row.getOrDefault("D", ""))) {
                        readingPositions = true;
                    }
                    continue;
                }

                if ("Orders".equalsIgnoreCase(firstCell)) {
                    break;
                }

                if (!StringUtils.hasText(firstCell)
                        && !StringUtils.hasText(row.getOrDefault("B", ""))
                        && !StringUtils.hasText(row.getOrDefault("C", ""))
                        && !StringUtils.hasText(row.getOrDefault("D", ""))) {
                    continue;
                }

                Mt5PositionRow position = parsePositionRow(row);
                if (position == null) {
                    skippedRows++;
                    continue;
                }

                positions.add(position);
            }

            return new ParsedReport(accountLabel, positions, skippedRows);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to read the MT5 .xlsx file. Please upload the original MT5 Excel export.");
        }
    }

    private boolean isPositionsHeaderRow(Row row, DataFormatter formatter) {
        return "Time".equalsIgnoreCase(getCellText(row, 0, formatter))
                && "Position".equalsIgnoreCase(getCellText(row, 1, formatter))
                && "Symbol".equalsIgnoreCase(getCellText(row, 2, formatter))
                && "Type".equalsIgnoreCase(getCellText(row, 3, formatter));
    }

    private boolean isBlankDataRow(Row row, DataFormatter formatter) {
        return !StringUtils.hasText(getCellText(row, 0, formatter))
                && !StringUtils.hasText(getCellText(row, 1, formatter))
                && !StringUtils.hasText(getCellText(row, 2, formatter))
                && !StringUtils.hasText(getCellText(row, 3, formatter));
    }

    private Mt5PositionRow parsePositionRow(Row row, DataFormatter formatter) {
        LocalDateTime entryTime = parseDateTime(getCellText(row, 0, formatter));
        String positionId = normalizePositionId(getCellText(row, 1, formatter));
        String symbol = normalizeUpper(getCellText(row, 2, formatter));
        String direction = normalizeDirection(getCellText(row, 3, formatter));
        double volume = parseDouble(getCell(row, 4), formatter);
        double entryPrice = parseDouble(getCell(row, 5), formatter);
        double stopLoss = parseDouble(getCell(row, 6), formatter);
        double takeProfit = parseDouble(getCell(row, 7), formatter);
        LocalDateTime exitTime = parseDateTime(getCellText(row, 8, formatter));
        double exitPrice = parseDouble(getCell(row, 9), formatter);
        double commission = parseDouble(getCell(row, 10), formatter);
        double swap = parseDouble(getCell(row, 11), formatter);
        double profit = parseDouble(getCell(row, 12), formatter);

        if (entryTime == null || exitTime == null) {
            return null;
        }
        if (!StringUtils.hasText(symbol) || !StringUtils.hasText(direction)) {
            return null;
        }
        if (volume <= 0 || entryPrice <= 0 || exitPrice <= 0) {
            return null;
        }

        return new Mt5PositionRow(
                positionId,
                symbol,
                direction,
                volume,
                entryTime,
                exitTime,
                entryPrice,
                exitPrice,
                stopLoss,
                takeProfit,
                commission,
                swap,
                profit
        );
    }

    private Mt5PositionRow parsePositionRow(Map<String, String> row) {
        LocalDateTime entryTime = parseDateTime(row.getOrDefault("A", ""));
        String positionId = normalizePositionId(row.getOrDefault("B", ""));
        String symbol = normalizeUpper(row.getOrDefault("C", ""));
        String direction = normalizeDirection(row.getOrDefault("D", ""));
        double volume = parseDouble(row.getOrDefault("E", ""));
        double entryPrice = parseDouble(row.getOrDefault("F", ""));
        double stopLoss = parseDouble(row.getOrDefault("G", ""));
        double takeProfit = parseDouble(row.getOrDefault("H", ""));
        LocalDateTime exitTime = parseDateTime(row.getOrDefault("I", ""));
        double exitPrice = parseDouble(row.getOrDefault("J", ""));
        double commission = parseDouble(row.getOrDefault("K", ""));
        double swap = parseDouble(row.getOrDefault("L", ""));
        double profit = parseDouble(row.getOrDefault("M", ""));

        if (entryTime == null || exitTime == null) {
            return null;
        }
        if (!StringUtils.hasText(symbol) || !StringUtils.hasText(direction)) {
            return null;
        }
        if (volume <= 0 || entryPrice <= 0 || exitPrice <= 0) {
            return null;
        }

        return new Mt5PositionRow(
                positionId,
                symbol,
                direction,
                volume,
                entryTime,
                exitTime,
                entryPrice,
                exitPrice,
                stopLoss,
                takeProfit,
                commission,
                swap,
                profit
        );
    }

    private Setup resolveOrCreateSetup(User user, String setupName) {
        return setupRepository.findByUserIdAndNameIgnoreCase(user.getId(), setupName)
                .orElseGet(() -> {
                    Setup setup = new Setup();
                    setup.setUser(user);
                    setup.setName(setupName);
                    setup.setDescription("Auto-created for MT5 history imports.");
                    setup.setActive(true);
                    return setupRepository.save(setup);
                });
    }

    private boolean isDuplicate(User user, Mt5PositionRow row) {
        String normalizedPositionId = normalizePositionId(row.positionId());
        if (StringUtils.hasText(normalizedPositionId)) {
            return tradeRepository.existsByUserIdAndMt5PositionId(user.getId(), normalizedPositionId)
                    || tradeRepository.countLegacyMt5ImportsByUserIdAndPositionId(user.getId(), normalizedPositionId) > 0;
        }
        return tradeRepository.existsByUserIdAndEntryTimeAndExitTimeAndSymbolIgnoreCaseAndDirectionIgnoreCaseAndPositionSizeAndEntryPrice(
                user.getId(),
                row.entryTime(),
                row.exitTime(),
                row.symbol(),
                row.direction(),
                row.volume(),
                row.entryPrice()
        );
    }

    private boolean isDuplicate(User user, ImportPreviewTrade row) {
        String normalizedPositionId = normalizePositionId(row.positionId());
        if (StringUtils.hasText(normalizedPositionId)) {
            return tradeRepository.existsByUserIdAndMt5PositionId(user.getId(), normalizedPositionId)
                    || tradeRepository.countLegacyMt5ImportsByUserIdAndPositionId(user.getId(), normalizedPositionId) > 0;
        }
        return tradeRepository.existsByUserIdAndEntryTimeAndExitTimeAndSymbolIgnoreCaseAndDirectionIgnoreCaseAndPositionSizeAndEntryPrice(
                user.getId(),
                row.entryTime(),
                row.exitTime(),
                row.symbol(),
                row.direction(),
                row.volume(),
                row.entryPrice()
        );
    }

    private ImportPreviewTrade preparePreviewTrade(Mt5PositionRow row, String sessionMode, boolean duplicate) {
        boolean hasReportedStopLoss = row.stopLoss() > 0;
        boolean hasReportedTakeProfit = row.takeProfit() > 0;

        double resolvedStopLoss = hasReportedStopLoss
                ? row.stopLoss()
                : fallbackStopLoss(row.direction(), row.entryPrice(), row.exitPrice(), row.profit());
        double resolvedTakeProfit = hasReportedTakeProfit
                ? row.takeProfit()
                : fallbackTakeProfit(row.direction(), row.entryPrice(), row.exitPrice(), row.profit());

        double netPnl = round2(row.profit() + row.commission() + row.swap());
        return new ImportPreviewTrade(
                row.positionId(),
                row.symbol(),
                row.direction(),
                row.volume(),
                row.entryTime(),
                row.exitTime(),
                row.entryPrice(),
                row.exitPrice(),
                round5(resolvedStopLoss),
                round5(resolvedTakeProfit),
                netPnl,
                resolveResult(netPnl),
                resolveSession(sessionMode, row.entryTime()),
                buildImportNote(row, netPnl, hasReportedStopLoss, hasReportedTakeProfit),
                !hasReportedStopLoss,
                !hasReportedTakeProfit,
                duplicate
        );
    }

    private Trade buildTrade(
            User user,
            Setup setup,
            String accountLabel,
            String defaultHtf,
            String defaultLtf,
            ImportPreviewTrade row
    ) {
        Trade trade = new Trade();
        trade.setUser(user);
        trade.setSetup(setup);
        trade.setTradeDate(row.entryTime());
        trade.setEntryTime(row.entryTime());
        trade.setExitTime(row.exitTime());
        trade.setAccountLabel(accountLabel);
        trade.setSymbol(row.symbol());
        trade.setDirection(row.direction());
        trade.setHtf(defaultHtf);
        trade.setLtf(defaultLtf);
        trade.setEntryPrice(round5(row.entryPrice()));
        trade.setExitPrice(round5(row.exitPrice()));
        trade.setPositionSize(round5(row.volume()));
        trade.setMt5PositionId(normalizePositionId(row.positionId()));
        trade.setStopLoss(round5(row.stopLoss()));
        trade.setInitialStopLoss(null);
        trade.setInitialStopLossConfirmed(false);
        trade.setTakeProfit(round5(row.takeProfit()));
        trade.setPnl(row.pnl());
        trade.setResult(row.result());
        trade.setRMultiple(0.0);
        trade.setRMultipleSource("UNKNOWN");
        trade.setSession(row.session());
        trade.setNote(row.note());

        return trade;
    }

    private String buildImportNote(Mt5PositionRow row, double netPnl, boolean hasReportedStopLoss, boolean hasReportedTakeProfit) {
        StringBuilder note = new StringBuilder("Imported from MT5 history");
        if (StringUtils.hasText(row.positionId())) {
            note.append(" | Position #").append(row.positionId());
        }
        note.append(" | Gross Profit: ").append(round2(row.profit()));
        note.append(" | Commission: ").append(round2(row.commission()));
        note.append(" | Swap: ").append(round2(row.swap()));
        note.append(" | Net PnL: ").append(round2(netPnl));
        if (!hasReportedStopLoss) {
            note.append(" | Stop loss placeholder used");
        }
        if (!hasReportedTakeProfit) {
            note.append(" | Take profit placeholder used");
        }
        return note.toString();
    }

    private String resolveResult(double netPnl) {
        if (netPnl > 0) {
            return "WIN";
        }
        if (netPnl < 0) {
            return "LOSS";
        }
        return "BE";
    }

    private String resolveSession(String sessionMode, LocalDateTime entryTime) {
        String normalized = normalizeUpper(sessionMode);
        if (!StringUtils.hasText(normalized) || "AUTO".equals(normalized)) {
            int hour = entryTime.getHour();
            if (hour >= 0 && hour < 7) {
                return "ASIA";
            }
            if (hour >= 7 && hour < 13) {
                return "LONDON";
            }
            if (hour >= 13 && hour < 22) {
                return "NEW_YORK";
            }
            return "OTHER";
        }
        return normalized;
    }

    private String normalizeDirection(String rawValue) {
        String normalized = normalizeUpper(rawValue);
        if ("BUY".equals(normalized) || "SELL".equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private String normalizeUpper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ENGLISH);
    }

    private String normalizePositionId(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private LocalDateTime parseDateTime(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        try {
            return LocalDateTime.parse(rawValue.trim(), MT5_DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private Cell getCell(Row row, int index) {
        return row == null ? null : row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    }

    private String getCellText(Row row, int index, DataFormatter formatter) {
        Cell cell = getCell(row, index);
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private double parseDouble(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return 0.0;
        }
        String rawValue = formatter.formatCellValue(cell);
        if (!StringUtils.hasText(rawValue)) {
            return 0.0;
        }

        String normalized = rawValue.replace(",", "").trim();
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private double parseDouble(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return 0.0;
        }
        String normalized = rawValue.replace(",", "").trim();
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private Map<String, byte[]> unzipEntries(MultipartFile file) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        int entryCount = 0;
        int totalBytes = 0;
        try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_XLSX_ENTRY_COUNT) {
                    throw new IllegalArgumentException("The uploaded MT5 workbook contains too many entries to import safely.");
                }
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue;
                }

                String entryName = entry.getName();
                if (entries.containsKey(entryName)) {
                    throw new IllegalArgumentException("The uploaded MT5 workbook contains duplicate XML entries.");
                }
                if (!XLSX_ALLOWED_XML_ENTRIES.contains(entryName)) {
                    zipInputStream.closeEntry();
                    continue;
                }

                int remainingBytes = MAX_XLSX_TOTAL_BYTES - totalBytes;
                if (remainingBytes <= 0) {
                    throw new IllegalArgumentException("The uploaded MT5 workbook is too large to import safely.");
                }
                byte[] entryBytes = readEntryBytes(zipInputStream, remainingBytes);
                totalBytes += entryBytes.length;
                entries.put(entryName, entryBytes);
                zipInputStream.closeEntry();
            }
        }
        return entries;
    }

    private byte[] readEntryBytes(ZipInputStream zipInputStream, int remainingBytes) throws IOException {
        int limit = Math.min(MAX_XLSX_ENTRY_BYTES, remainingBytes);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.min(limit, ZIP_BUFFER_SIZE));
        byte[] buffer = new byte[ZIP_BUFFER_SIZE];
        int totalRead = 0;

        int read;
        while ((read = zipInputStream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > limit) {
                throw new IllegalArgumentException("The uploaded MT5 workbook is too large to import safely.");
            }
            outputStream.write(buffer, 0, read);
        }

        return outputStream.toByteArray();
    }

    private List<String> parseSharedStrings(byte[] sharedStringsBytes) throws Exception {
        List<String> sharedStrings = new ArrayList<>();
        if (sharedStringsBytes == null || sharedStringsBytes.length == 0) {
            return sharedStrings;
        }

        Document document = parseXml(sharedStringsBytes);
        NodeList items = document.getElementsByTagNameNS("*", "si");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            NodeList texts = item.getElementsByTagNameNS("*", "t");
            StringBuilder value = new StringBuilder();
            for (int j = 0; j < texts.getLength(); j++) {
                value.append(texts.item(j).getTextContent());
            }
            sharedStrings.add(value.toString());
        }
        return sharedStrings;
    }

    private List<Map<String, String>> parseSheetRows(byte[] sheetBytes, List<String> sharedStrings) throws Exception {
        Document document = parseXml(sheetBytes);
        NodeList rowNodes = document.getElementsByTagNameNS("*", "row");
        List<Map<String, String>> rows = new ArrayList<>();

        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element rowElement = (Element) rowNodes.item(i);
            NodeList cellNodes = rowElement.getElementsByTagNameNS("*", "c");
            Map<String, String> rowValues = new HashMap<>();

            for (int j = 0; j < cellNodes.getLength(); j++) {
                Element cellElement = (Element) cellNodes.item(j);
                String reference = cellElement.getAttribute("r");
                String column = extractColumn(reference);
                if (!StringUtils.hasText(column)) {
                    continue;
                }

                String rawType = cellElement.getAttribute("t");
                String rawValue = firstChildText(cellElement, "v");
                if ("s".equals(rawType) && StringUtils.hasText(rawValue)) {
                    int sharedIndex = Integer.parseInt(rawValue);
                    rowValues.put(column, sharedIndex >= 0 && sharedIndex < sharedStrings.size() ? sharedStrings.get(sharedIndex) : "");
                } else {
                    rowValues.put(column, rawValue == null ? "" : rawValue.trim());
                }
            }

            rows.add(rowValues);
        }

        return rows;
    }

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(inputStream);
        }
    }

    private String firstChildText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return "";
        }
        Node node = nodes.item(0);
        return node == null ? "" : node.getTextContent();
    }

    private String extractColumn(String reference) {
        if (!StringUtils.hasText(reference)) {
            return "";
        }
        StringBuilder column = new StringBuilder();
        for (int i = 0; i < reference.length(); i++) {
            char current = reference.charAt(i);
            if (Character.isLetter(current)) {
                column.append(current);
            } else {
                break;
            }
        }
        return column.toString();
    }

    private double fallbackStopLoss(String direction, double entryPrice, double exitPrice, double profit) {
        if ("BUY".equals(direction)) {
            if (profit < 0 && exitPrice > 0 && exitPrice < entryPrice) {
                return exitPrice;
            }
            return entryPrice * 0.995;
        }

        if (profit < 0 && exitPrice > 0 && exitPrice > entryPrice) {
            return exitPrice;
        }
        return entryPrice * 1.005;
    }

    private double fallbackTakeProfit(String direction, double entryPrice, double exitPrice, double profit) {
        if ("BUY".equals(direction)) {
            if (profit > 0 && exitPrice > entryPrice) {
                return exitPrice;
            }
            return entryPrice * 1.005;
        }

        if (profit > 0 && exitPrice < entryPrice) {
            return exitPrice;
        }
        return entryPrice * 0.995;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round5(double value) {
        return Math.round(value * 100000.0) / 100000.0;
    }

    public record ImportOptions(
            String setupName,
            String defaultHtf,
            String defaultLtf,
            String sessionMode
    ) {
    }

    public record ImportResult(
            int importedCount,
            int duplicateCount,
            int skippedCount,
            String accountLabel,
            String setupName
    ) {
    }

    public record ImportPreview(
            String previewId,
            String userId,
            String sourceFileName,
            String accountLabel,
            String setupName,
            String defaultHtf,
            String defaultLtf,
            String sessionMode,
            int closedPositionCount,
            int importableCount,
            int duplicateCount,
            int skippedCount,
            boolean proAccess,
            long remainingTradeSlots,
            boolean canImport,
            String blockingMessage,
            List<ImportPreviewTrade> previewTrades
    ) implements Serializable {
    }

    public record ImportPreviewTrade(
            String positionId,
            String symbol,
            String direction,
            double volume,
            LocalDateTime entryTime,
            LocalDateTime exitTime,
            double entryPrice,
            double exitPrice,
            double stopLoss,
            double takeProfit,
            double pnl,
            String result,
            String session,
            String note,
            boolean placeholderStopLoss,
            boolean placeholderTakeProfit,
            boolean duplicate
    ) implements Serializable {
    }

    private record ParsedReport(
            String accountLabel,
            List<Mt5PositionRow> positions,
            int skippedRows
    ) {
    }

    private record Mt5PositionRow(
            String positionId,
            String symbol,
            String direction,
            double volume,
            LocalDateTime entryTime,
            LocalDateTime exitTime,
            double entryPrice,
            double exitPrice,
            double stopLoss,
            double takeProfit,
            double commission,
            double swap,
            double profit
    ) {
    }
}
