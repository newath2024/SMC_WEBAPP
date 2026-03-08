package com.example.demo.service;

import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.repository.TradeRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TradeService {

    private final TradeRepository repo;
    private final PnLCalculator pnlCalculator;

    public TradeService(TradeRepository repo, PnLCalculator pnlCalculator) {
        this.repo = repo;
        this.pnlCalculator = pnlCalculator;
    }

    public Trade saveForUser(Trade trade, User user) {
        if (trade == null) {
            throw new IllegalArgumentException("Trade must not be null");
        }

        if (user == null) {
            throw new IllegalArgumentException("User must not be null");
        }

        trade.setUser(user);

        normalizeTrade(trade);
        autoCalculateMetrics(trade);

        return repo.save(trade);
    }

    public List<Trade> findAllByUser(String userId) {
        return repo.findByUserIdOrderByEntryTimeDesc(userId);
    }

    public Trade findByIdForUser(String tradeId, String userId) {
        return repo.findByIdAndUserId(tradeId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Trade not found: " + tradeId));
    }

    public Trade findEditableByIdForUser(String tradeId, String userId) {
        return findByIdForUser(tradeId, userId);
    }

    public Trade findByIdForAdmin(String id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trade not found: " + id));
    }

    public List<Trade> findAllForAdmin() {
        return repo.findAll();
    }

    public Trade updateForUser(String tradeId, Trade formTrade, User currentUser) {
        if (currentUser == null) {
            throw new IllegalArgumentException("Current user must not be null");
        }

        Trade existing = findByIdForUser(tradeId, currentUser.getId());

        applyEditableFields(existing, formTrade);
        normalizeTrade(existing);
        autoCalculateMetrics(existing);

        return repo.save(existing);
    }

    public Trade updateForAdmin(String tradeId, Trade formTrade) {
        Trade existing = findByIdForAdmin(tradeId);

        applyEditableFields(existing, formTrade);
        normalizeTrade(existing);
        autoCalculateMetrics(existing);

        return repo.save(existing);
    }

    public void deleteForUser(String tradeId, String userId) {
        Trade trade = findByIdForUser(tradeId, userId);
        repo.delete(trade);
    }

    public void deleteForAdmin(String tradeId) {
        Trade trade = findByIdForAdmin(tradeId);
        repo.delete(trade);
    }

    private void applyEditableFields(Trade existing, Trade formTrade) {
        existing.setTradeDate(formTrade.getTradeDate());
        existing.setEntryTime(formTrade.getEntryTime());
        existing.setExitTime(formTrade.getExitTime());

        existing.setAccountLabel(formTrade.getAccountLabel());
        existing.setSymbol(formTrade.getSymbol());
        existing.setDirection(formTrade.getDirection());
        existing.setHtf(formTrade.getHtf());
        existing.setLtf(formTrade.getLtf());

        existing.setEntryPrice(formTrade.getEntryPrice());
        existing.setStopLoss(formTrade.getStopLoss());
        existing.setTakeProfit(formTrade.getTakeProfit());
        existing.setExitPrice(formTrade.getExitPrice());
        existing.setPositionSize(formTrade.getPositionSize());

        existing.setResult(formTrade.getResult());
        existing.setSetup(formTrade.getSetup());
        existing.setSession(formTrade.getSession());
        existing.setNote(formTrade.getNote());
    }

    private void normalizeTrade(Trade trade) {
        if (trade.getAccountLabel() != null) {
            trade.setAccountLabel(trade.getAccountLabel().trim());
        }

        if (trade.getDirection() != null) {
            trade.setDirection(trade.getDirection().trim().toUpperCase());
        }

        if (trade.getResult() != null) {
            String result = trade.getResult().trim();
            if ("BE and take partial".equalsIgnoreCase(result)) {
                trade.setResult("BE and take partial");
            } else {
                trade.setResult(result.toUpperCase());
            }
        }

        if (trade.getSession() != null) {
            trade.setSession(trade.getSession().trim().toUpperCase());
        }

        if (trade.getSymbol() != null) {
            trade.setSymbol(trade.getSymbol().trim().toUpperCase());
        }

        if (trade.getHtf() != null) {
            trade.setHtf(trade.getHtf().trim().toUpperCase());
        }

        if (trade.getLtf() != null) {
            trade.setLtf(trade.getLtf().trim().toUpperCase());
        }

        if (trade.getTradeDate() == null && trade.getEntryTime() != null) {
            trade.setTradeDate(trade.getEntryTime());
        }
    }

    private void autoCalculateMetrics(Trade trade) {
        trade.setRMultiple(calculateRMultiple(trade));
        trade.setPnl(calculatePnL(trade));
    }

    public double calculateRMultiple(Trade trade) {
        String direction = trade.getDirection();
        String result = trade.getResult();

        double entry = trade.getEntryPrice();
        double stopLoss = trade.getStopLoss();
        double exit = trade.getExitPrice();

        if (direction == null || direction.isBlank()) {
            return 0.0;
        }

        if ("LOSS".equalsIgnoreCase(result)) {
            return -1.0;
        }

        if (entry <= 0 || stopLoss <= 0 || exit <= 0) {
            return 0.0;
        }

        double risk;
        double reward;

        if ("BUY".equalsIgnoreCase(direction)) {
            risk = entry - stopLoss;
            reward = exit - entry;
        } else if ("SELL".equalsIgnoreCase(direction)) {
            risk = stopLoss - entry;
            reward = entry - exit;
        } else {
            return 0.0;
        }

        if (risk <= 0) {
            return 0.0;
        }

        return round2(reward / risk);
    }

    public double calculatePnL(Trade trade) {
        return pnlCalculator.calculate(trade);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}