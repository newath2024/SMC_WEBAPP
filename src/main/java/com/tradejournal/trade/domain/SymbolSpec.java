package com.tradejournal.trade.domain;

public class SymbolSpec {
    private final String symbol;
    private final double tickSize;
    private final double tickValuePerLot;
    private final boolean jpyPair;
    private final boolean crypto;

    public SymbolSpec(String symbol, double tickSize, double tickValuePerLot, boolean jpyPair, boolean crypto) {
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.tickValuePerLot = tickValuePerLot;
        this.jpyPair = jpyPair;
        this.crypto = crypto;
    }

    public String getSymbol() {
        return symbol;
    }

    public double getTickSize() {
        return tickSize;
    }

    public double getTickValuePerLot() {
        return tickValuePerLot;
    }

    public boolean isJpyPair() {
        return jpyPair;
    }

    public boolean isCrypto() {
        return crypto;
    }
}
