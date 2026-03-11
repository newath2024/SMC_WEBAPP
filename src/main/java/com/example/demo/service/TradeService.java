package com.example.demo.service;

import com.example.demo.entity.MistakeTag;
import com.example.demo.entity.Setup;
import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeMistakeTag;
import com.example.demo.entity.User;
import com.example.demo.repository.MistakeTagRepository;
import com.example.demo.repository.SetupRepository;
import com.example.demo.repository.TradeMistakeTagRepository;
import com.example.demo.repository.TradeReviewRepository;
import com.example.demo.repository.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class TradeService {

    private final TradeRepository repo;
    private final PnLCalculator pnlCalculator;
    private final TradeMistakeTagRepository tradeMistakeTagRepository;
    private final MistakeTagRepository mistakeTagRepository;
    private final SetupRepository setupRepository;
    private final MistakeTagService mistakeTagService;
    private final TradeReviewRepository tradeReviewRepository;
    
    public TradeService(
            TradeRepository repo,
            PnLCalculator pnlCalculator,
            TradeMistakeTagRepository tradeMistakeTagRepository,
            MistakeTagRepository mistakeTagRepository,
            SetupRepository setupRepository,
            MistakeTagService mistakeTagService,
            TradeReviewRepository tradeReviewRepository
    ) {
        this.repo = repo;
        this.pnlCalculator = pnlCalculator;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
        this.mistakeTagRepository = mistakeTagRepository;
        this.setupRepository = setupRepository;
        this.mistakeTagService = mistakeTagService;
        this.tradeReviewRepository = tradeReviewRepository;
    }

    @Transactional
    public Trade saveForUser(Trade trade, User user, List<String> mistakeIds, String customMistakes) {
        if (trade == null) {
            throw new IllegalArgumentException("Trade must not be null");
        }

        if (user == null) {
            throw new IllegalArgumentException("User must not be null");
        }

        trade.setUser(user);
        trade.setSetup(resolveSetupForUser(trade.getSetup(), user.getId()));

        normalizeTrade(trade);
        autoCalculateMetrics(trade);

        Trade saved = repo.save(trade);
        replaceMistakes(saved, mistakeIds, customMistakes);
        loadMistakes(saved);

        return saved;
    }

    public List<Trade> findAllByUser(String userId) {
        List<Trade> trades = repo.findByUserIdOrderByEntryTimeDesc(userId);
        for (Trade trade : trades) {
            loadMistakes(trade);
        }
        return trades;
    }

    public Trade findByIdForUser(String tradeId, String userId) {
        Trade trade = repo.findByIdAndUserId(tradeId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Trade not found: " + tradeId));

        loadMistakes(trade);
        return trade;
    }

    public Trade findEditableByIdForUser(String tradeId, String userId) {
        return findByIdForUser(tradeId, userId);
    }

    public Trade findByIdForAdmin(String id) {
        Trade trade = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trade not found: " + id));

        loadMistakes(trade);
        return trade;
    }

    public List<Trade> findAllForAdmin() {
        List<Trade> trades = repo.findAll();
        for (Trade trade : trades) {
            loadMistakes(trade);
        }
        return trades;
    }

    @Transactional
    public Trade updateForUser(String tradeId, Trade formTrade, User currentUser, List<String> mistakeIds, String customMistakes) {
        if (currentUser == null) {
            throw new IllegalArgumentException("Current user must not be null");
        }

        Trade existing = findByIdForUser(tradeId, currentUser.getId());

        applyEditableFields(existing, formTrade);
        existing.setSetup(resolveSetupForUser(formTrade.getSetup(), currentUser.getId()));
        normalizeTrade(existing);
        autoCalculateMetrics(existing);

        Trade saved = repo.save(existing);
        replaceMistakes(saved, mistakeIds, customMistakes);
        loadMistakes(saved);

        return saved;
    }

    @Transactional
    public Trade updateForAdmin(String tradeId, Trade formTrade, List<String> mistakeIds, String customMistakes) {
        Trade existing = findByIdForAdmin(tradeId);

        applyEditableFields(existing, formTrade);
        existing.setSetup(resolveSetupForAdmin(formTrade.getSetup()));
        normalizeTrade(existing);
        autoCalculateMetrics(existing);

        Trade saved = repo.save(existing);
        replaceMistakes(saved, mistakeIds, customMistakes);
        loadMistakes(saved);

        return saved;
    }

    @Transactional
    public void deleteForUser(String tradeId, String userId) {
        Trade trade = findByIdForUser(tradeId, userId);
        tradeReviewRepository.deleteByTradeId(trade.getId());
        tradeMistakeTagRepository.deleteByTradeId(trade.getId());
        repo.delete(trade);
    }

    @Transactional
    public void deleteForAdmin(String tradeId) {
        Trade trade = findByIdForAdmin(tradeId);
        tradeReviewRepository.deleteByTradeId(trade.getId());
        tradeMistakeTagRepository.deleteByTradeId(trade.getId());
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

    private void replaceMistakes(Trade trade, List<String> mistakeIds, String customMistakes) {
        tradeMistakeTagRepository.deleteByTradeId(trade.getId());

        List<MistakeTag> selectedTags = new ArrayList<>();

        if (mistakeIds != null && !mistakeIds.isEmpty()) {
            selectedTags.addAll(mistakeTagRepository.findAllById(mistakeIds));
        }

        if (customMistakes != null && !customMistakes.isBlank()) {
            String[] parts = customMistakes.split(",");

            for (String part : parts) {
                String raw = part.trim();
                if (raw.isBlank()) {
                    continue;
                }

                MistakeTag tag = mistakeTagService.findOrCreateByName(raw);

                boolean alreadyAdded = selectedTags.stream()
                        .anyMatch(existing -> existing.getId().equals(tag.getId()));

                if (!alreadyAdded) {
                    selectedTags.add(tag);
                }
            }
        }

        for (MistakeTag tag : selectedTags) {
            TradeMistakeTag link = new TradeMistakeTag();
            link.setTrade(trade);
            link.setMistakeTag(tag);
            tradeMistakeTagRepository.save(link);
        }

        trade.setMistakes(selectedTags);
    }

    private void loadMistakes(Trade trade) {
        List<TradeMistakeTag> links = tradeMistakeTagRepository.findByTradeId(trade.getId());
        List<MistakeTag> mistakes = new ArrayList<>();

        for (TradeMistakeTag link : links) {
            mistakes.add(link.getMistakeTag());
        }

        trade.setMistakes(mistakes);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Setup resolveSetupForUser(Setup rawSetup, String userId) {
        String setupId = extractSetupId(rawSetup);
        return setupRepository.findByIdAndUserId(setupId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid setup selected"));
    }

    private Setup resolveSetupForAdmin(Setup rawSetup) {
        String setupId = extractSetupId(rawSetup);
        return setupRepository.findById(setupId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid setup selected"));
    }

    private String extractSetupId(Setup rawSetup) {
        if (rawSetup == null || rawSetup.getId() == null || rawSetup.getId().isBlank()) {
            throw new IllegalArgumentException("Setup is required");
        }
        return rawSetup.getId().trim();
    }
}

