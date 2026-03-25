package com.tradejournal.service;

import com.tradejournal.entity.Trade;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class PnLCalculator {

    private final Map<String, SymbolSpec> specs = new HashMap<>();

    public PnLCalculator() {
        specs.put("XAUUSD", new SymbolSpec("XAUUSD", 0.01, 1.0, false, false));
        specs.put("EURUSD", new SymbolSpec("EURUSD", 0.0001, 10.0, false, false));
        specs.put("GBPUSD", new SymbolSpec("GBPUSD", 0.0001, 10.0, false, false));
        specs.put("USDJPY", new SymbolSpec("USDJPY", 0.01, 0.0, true, false));

        // crypto: positionSize = số coin
        specs.put("BTCUSD", new SymbolSpec("BTCUSD", 1.0, 0.0, false, true));
        specs.put("ETHUSD", new SymbolSpec("ETHUSD", 0.01, 0.0, false, true));
    }

    public double calculate(Trade trade) {
        if (trade == null || trade.getSymbol() == null || trade.getDirection() == null) {
            return 0.0;
        }

        String symbol = trade.getSymbol().trim().toUpperCase();
        String direction = trade.getDirection().trim().toUpperCase();

        double entry = trade.getEntryPrice();
        double exit = trade.getExitPrice();
        double size = trade.getPositionSize();

        if (entry <= 0 || exit <= 0 || size <= 0) {
            return 0.0;
        }

        SymbolSpec spec = specs.get(symbol);
        if (spec == null) {
            return 0.0;
        }

        double priceDiff;
        if ("BUY".equals(direction)) {
            priceDiff = exit - entry;
        } else if ("SELL".equals(direction)) {
            priceDiff = entry - exit;
        } else {
            return 0.0;
        }

        // BTC/ETH: size = số coin
        if (spec.isCrypto()) {
            return round2(priceDiff * size);
        }

        // USDJPY: pip value theo giá hiện tại
        if (spec.isJpyPair()) {
            double pips = priceDiff / spec.getTickSize();
            double pipValuePerLotUsd = 1000.0 / exit;
            return round2(pips * pipValuePerLotUsd * size);
        }

        // XAUUSD, EURUSD, GBPUSD
        double ticks = priceDiff / spec.getTickSize();
        return round2(ticks * spec.getTickValuePerLot() * size);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
