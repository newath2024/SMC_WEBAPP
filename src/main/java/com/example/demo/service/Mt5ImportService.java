package com.example.demo.service;

import com.example.demo.entity.Setup;
import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.repository.SetupRepository;
import com.example.demo.repository.TradeRepository;
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

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class Mt5ImportService {

    private static final DateTimeFormatter MT5_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy.MM.dd HH:mm")
            .optionalStart()
            .appendPattern(":ss")
            .optionalEnd()
            .toFormatter(Locale.ENGLISH);

    private final TradeRepository tradeRepository;
    private final SetupRepository setupRepository;
    private final UserService userService;

    public Mt5ImportService(
            TradeRepository tradeRepository,
            SetupRepository setupRepository,
            UserService userService
    ) {
        this.tradeRepository = tradeRepository;
        this.setupRepository = setupRepository;
        this.userService = userService;
    }

    @Transactional
    public ImportResult importWorkbook(MultipartFile file, User user, ImportOptions options) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose an MT5 history file to import.");
        }

        if (user == null) {
            throw new IllegalArgumentException("You must be logged in to import trades.");
        }

        String setupName = normalizeOrDefault(options.setupName(), "MT5 Import");
        String defaultHtf = normalizeOrDefault(options.defaultHtf(), "H1");
        String defaultLtf = normalizeOrDefault(options.defaultLtf(), "M5");
        String sessionMode = normalizeOrDefault(options.sessionMode(), "AUTO");

        ParsedReport report = parseReport(file);
        if (report.positions().isEmpty()) {
            throw new IllegalArgumentException("No closed positions were found in the MT5 report.");
        }

        Setup setup = resolveOrCreateSetup(user, setupName);
        String accountLabel = normalizeOrDefault(report.accountLabel(), "MT5 Account");
        long existingTradeCount = tradeRepository.countByUserId(user.getId());

        List<Trade> tradesToSave = new ArrayList<>();
        int duplicateCount = 0;

        for (Mt5PositionRow row : report.positions()) {
            if (isDuplicate(user, row)) {
                duplicateCount++;
                continue;
            }

            tradesToSave.add(buildTrade(user, setup, accountLabel, defaultHtf, defaultLtf, sessionMode, row));
        }

        int importableCount = tradesToSave.size();
        int tradeLimit = userService.resolveTradeLimit(user);
        if (!userService.hasProAccess(user) && existingTradeCount + importableCount > tradeLimit) {
            long remaining = Math.max(0, tradeLimit - existingTradeCount);
            throw new IllegalStateException(
                    "This import would exceed your free plan limit. Remaining slots: " + remaining + ". Upgrade to Pro or import a smaller file.");
        }

        if (!tradesToSave.isEmpty()) {
            tradeRepository.saveAll(tradesToSave);
        }

        return new ImportResult(
                tradesToSave.size(),
                duplicateCount,
                report.skippedRows(),
                accountLabel,
                setup.getName()
        );
    }

    private ParsedReport parseReport(MultipartFile file) {
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
        String positionId = getCellText(row, 1, formatter);
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

    private Trade buildTrade(
            User user,
            Setup setup,
            String accountLabel,
            String defaultHtf,
            String defaultLtf,
            String sessionMode,
            Mt5PositionRow row
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

        boolean hasReportedStopLoss = row.stopLoss() > 0;
        boolean hasReportedTakeProfit = row.takeProfit() > 0;

        double resolvedStopLoss = hasReportedStopLoss
                ? row.stopLoss()
                : fallbackStopLoss(row.direction(), row.entryPrice(), row.exitPrice(), row.profit());
        double resolvedTakeProfit = hasReportedTakeProfit
                ? row.takeProfit()
                : fallbackTakeProfit(row.direction(), row.entryPrice(), row.exitPrice(), row.profit());

        trade.setStopLoss(round5(resolvedStopLoss));
        trade.setTakeProfit(round5(resolvedTakeProfit));

        double netPnl = round2(row.profit() + row.commission() + row.swap());
        trade.setPnl(netPnl);
        trade.setResult(resolveResult(netPnl));
        trade.setRMultiple(hasReportedStopLoss ? round2(calculateRMultiple(trade)) : 0.0);
        trade.setSession(resolveSession(sessionMode, row.entryTime()));
        trade.setNote(buildImportNote(row, netPnl, hasReportedStopLoss, hasReportedTakeProfit));

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

    private double calculateRMultiple(Trade trade) {
        double entry = trade.getEntryPrice();
        double stopLoss = trade.getStopLoss();
        double exit = trade.getExitPrice();

        if ("BUY".equals(trade.getDirection())) {
            double risk = entry - stopLoss;
            if (risk <= 0) {
                return 0.0;
            }
            return (exit - entry) / risk;
        }

        if ("SELL".equals(trade.getDirection())) {
            double risk = stopLoss - entry;
            if (risk <= 0) {
                return 0.0;
            }
            return (entry - exit) / risk;
        }

        return 0.0;
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
