package com.tradejournal.trade.service;

import com.tradejournal.mistake.domain.MistakeTag;
import com.tradejournal.setup.domain.Setup;
import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.domain.TradeMistakeTag;
import com.tradejournal.auth.domain.User;
import com.tradejournal.mistake.service.MistakeTagService;
import com.tradejournal.mistake.repository.MistakeTagRepository;
import com.tradejournal.setup.repository.SetupRepository;
import com.tradejournal.trade.dto.TradeFilterCriteria;
import com.tradejournal.trade.repository.TradeMistakeTagRepository;
import com.tradejournal.trade.repository.TradeReviewRepository;
import com.tradejournal.trade.repository.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
        ensureInitialStopLoss(trade);

        normalizeTrade(trade);
        trade.setPnl(calculatePnL(trade));

        Trade saved = repo.save(trade);
        replaceMistakes(saved, user, mistakeIds, customMistakes);
        refreshRMultiplesForUser(user.getId());

        Trade refreshed = repo.findByIdAndUserId(saved.getId(), user.getId()).orElse(saved);
        loadMistakes(refreshed);
        return refreshed;
    }

    public List<Trade> findAllByUser(String userId) {
        List<Trade> trades = repo.findByUserIdOrderByEntryTimeDesc(userId);
        for (Trade trade : trades) {
            loadMistakes(trade);
        }
        return trades;
    }

    public long countByUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return 0L;
        }
        return repo.countByUserId(userId.trim());
    }

    public List<Trade> findFilteredByUser(String userId, TradeFilterCriteria criteria) {
        List<Trade> trades = findAllByUser(userId);
        return filterTrades(trades, criteria);
    }

    public List<String> findOwnedTradeIds(String userId, List<String> tradeIds) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }

        List<String> normalizedTradeIds = normalizeTradeIds(tradeIds);
        if (normalizedTradeIds.isEmpty()) {
            return List.of();
        }

        return repo.findByUserIdAndIdInOrderByEntryTimeDesc(userId.trim(), normalizedTradeIds).stream()
                .map(Trade::getId)
                .filter(StringUtils::hasText)
                .toList();
    }

    public List<String> findFilteredTradeIdsForUser(String userId, TradeFilterCriteria criteria) {
        return findFilteredByUser(userId, criteria).stream()
                .map(Trade::getId)
                .filter(StringUtils::hasText)
                .toList();
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
        existing.setPnl(calculatePnL(existing));

        Trade saved = repo.save(existing);
        replaceMistakes(saved, currentUser, mistakeIds, customMistakes);
        refreshRMultiplesForUser(currentUser.getId());

        Trade refreshed = repo.findByIdAndUserId(saved.getId(), currentUser.getId()).orElse(saved);
        loadMistakes(refreshed);
        return refreshed;
    }

    @Transactional
    public Trade updateForAdmin(String tradeId, Trade formTrade, List<String> mistakeIds, String customMistakes) {
        Trade existing = findByIdForAdmin(tradeId);
        String ownerUserId = existing.getUser() != null ? existing.getUser().getId() : null;

        applyEditableFields(existing, formTrade);
        existing.setSetup(resolveSetupForAdmin(formTrade.getSetup(), ownerUserId));
        normalizeTrade(existing);
        existing.setPnl(calculatePnL(existing));

        Trade saved = repo.save(existing);
        replaceMistakes(saved, saved.getUser(), mistakeIds, customMistakes);
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            refreshRMultiplesForUser(ownerUserId);
        }

        Trade refreshed = repo.findById(saved.getId()).orElse(saved);
        loadMistakes(refreshed);
        return refreshed;
    }

    @Transactional
    public void deleteForUser(String tradeId, String userId) {
        Trade trade = findByIdForUser(tradeId, userId);
        tradeReviewRepository.deleteByTradeId(trade.getId());
        tradeMistakeTagRepository.deleteByTradeId(trade.getId());
        repo.delete(trade);
        refreshRMultiplesForUser(userId);
    }

    @Transactional
    public void deleteForAdmin(String tradeId) {
        Trade trade = findByIdForAdmin(tradeId);
        String ownerUserId = trade.getUser() != null ? trade.getUser().getId() : null;
        tradeReviewRepository.deleteByTradeId(trade.getId());
        tradeMistakeTagRepository.deleteByTradeId(trade.getId());
        repo.delete(trade);
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            refreshRMultiplesForUser(ownerUserId);
        }
    }

    @Transactional
    public int deleteForUserIds(List<String> tradeIds, String userId) {
        if (!StringUtils.hasText(userId)) {
            return 0;
        }

        List<String> ownedTradeIds = findOwnedTradeIds(userId, tradeIds);
        if (ownedTradeIds.isEmpty()) {
            return 0;
        }

        tradeReviewRepository.deleteByTradeIdIn(ownedTradeIds);
        tradeMistakeTagRepository.deleteByTradeIdIn(ownedTradeIds);
        repo.deleteAllByIdInBatch(ownedTradeIds);
        refreshRMultiplesForUser(userId);
        return ownedTradeIds.size();
    }

    @Transactional
    public int deleteForAdminIds(List<String> tradeIds) {
        List<String> normalizedTradeIds = normalizeTradeIds(tradeIds);
        if (normalizedTradeIds.isEmpty()) {
            return 0;
        }

        List<Trade> trades = repo.findAllById(normalizedTradeIds);
        if (trades.isEmpty()) {
            return 0;
        }

        List<String> existingTradeIds = trades.stream()
                .map(Trade::getId)
                .filter(StringUtils::hasText)
                .toList();
        if (existingTradeIds.isEmpty()) {
            return 0;
        }

        Set<String> ownerUserIds = trades.stream()
                .map(Trade::getUser)
                .filter(Objects::nonNull)
                .map(User::getId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        tradeReviewRepository.deleteByTradeIdIn(existingTradeIds);
        tradeMistakeTagRepository.deleteByTradeIdIn(existingTradeIds);
        repo.deleteAllByIdInBatch(existingTradeIds);

        for (String ownerUserId : ownerUserIds) {
            refreshRMultiplesForUser(ownerUserId);
        }
        return existingTradeIds.size();
    }

    @Transactional
    public int deleteFilteredForUser(String userId, TradeFilterCriteria criteria) {
        return deleteForUserIds(findFilteredTradeIdsForUser(userId, criteria), userId);
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
        if (formTrade.getInitialStopLoss() != null && formTrade.getInitialStopLoss() > 0) {
            existing.setInitialStopLoss(formTrade.getInitialStopLoss());
            existing.setInitialStopLossConfirmed(true);
        }
        existing.setTakeProfit(formTrade.getTakeProfit());
        existing.setExitPrice(formTrade.getExitPrice());
        existing.setPositionSize(formTrade.getPositionSize());

        existing.setResult(formTrade.getResult());
        existing.setSetup(formTrade.getSetup());
        existing.setSession(formTrade.getSession());
        existing.setEstimatedHoldingMinutes(formTrade.getEstimatedHoldingMinutes());
        existing.setEstimatedLtfCandlesHeld(formTrade.getEstimatedLtfCandlesHeld());
        existing.setSessionGuess(formTrade.getSessionGuess());
        existing.setSessionConfidence(formTrade.getSessionConfidence());
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

        if (trade.getSessionGuess() != null) {
            trade.setSessionGuess(trade.getSessionGuess().trim());
        }

        if (trade.getSessionConfidence() != null) {
            trade.setSessionConfidence(trade.getSessionConfidence().trim().toLowerCase());
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

        if (trade.getEntryTime() != null) {
            trade.setSession(resolveSessionFromEntryTime(trade.getEntryTime()));
        } else if ((trade.getSession() == null || trade.getSession().isBlank())
                && trade.getSessionGuess() != null
                && !trade.getSessionGuess().isBlank()) {
            trade.setSession(trade.getSessionGuess().trim().toUpperCase().replace(' ', '_'));
        }
    }

    private String resolveSessionFromEntryTime(java.time.LocalDateTime entryTime) {
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

    @Transactional
    public void refreshAllUsersRMultiples() {
        List<Trade> trades = repo.findAll();
        if (trades.isEmpty()) {
            return;
        }

        Map<String, List<Trade>> tradesByUser = new HashMap<>();
        for (Trade trade : trades) {
            if (trade.getUser() == null || trade.getUser().getId() == null || trade.getUser().getId().isBlank()) {
                continue;
            }
            tradesByUser.computeIfAbsent(trade.getUser().getId(), ignored -> new ArrayList<>()).add(trade);
        }

        for (List<Trade> userTrades : tradesByUser.values()) {
            refreshRMultiples(userTrades);
        }

        repo.saveAll(trades);
    }

    @Transactional
    public void refreshRMultiplesForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }

        List<Trade> trades = repo.findByUserIdOrderByEntryTimeDesc(userId);
        if (trades.isEmpty()) {
            return;
        }

        refreshRMultiples(trades);
        repo.saveAll(trades);
    }

    public double calculateRMultiple(Trade trade) {
        RMultipleComputation computation = resolveRMultiple(trade, null);
        return computation.value() == null ? 0.0 : computation.value();
    }

    public double calculatePnL(Trade trade) {
        return pnlCalculator.calculate(trade);
    }

    private void refreshRMultiples(List<Trade> trades) {
        Double accountAverageLoss = calculateAverageLoss(trades.stream()
                .mapToDouble(Trade::getPnl)
                .filter(pnl -> pnl < 0)
                .map(Math::abs)
                .boxed()
                .toList());

        for (Trade trade : trades) {
            RMultipleComputation computation = resolveRMultiple(trade, accountAverageLoss);
            trade.setRMultiple(computation.value() == null ? 0.0 : computation.value());
            trade.setRMultipleSource(computation.source());
        }
    }

    private Double calculateAverageLoss(List<Double> losses) {
        if (losses == null || losses.isEmpty()) {
            return null;
        }

        double totalLoss = 0.0;
        int validLosses = 0;
        for (Double loss : losses) {
            if (loss == null || loss <= 0) {
                continue;
            }
            totalLoss += loss;
            validLosses++;
        }
        if (totalLoss <= 0 || validLosses == 0) {
            return null;
        }
        return round2(totalLoss / validLosses);
    }

    private RMultipleComputation resolveRMultiple(Trade trade, Double accountAverageLoss) {
        Double exactR = calculateExactRMultiple(trade);
        if (exactR != null) {
            return new RMultipleComputation(round2(exactR), "EXACT");
        }

        if (trade == null) {
            return new RMultipleComputation(null, "UNKNOWN");
        }
        if (trade.getDirection() == null || trade.getDirection().isBlank() || trade.getExitPrice() <= 0) {
            return new RMultipleComputation(null, "UNKNOWN");
        }

        if (accountAverageLoss != null && accountAverageLoss > 0) {
            return new RMultipleComputation(round2(trade.getPnl() / accountAverageLoss), "ESTIMATED_ACCOUNT");
        }

        return new RMultipleComputation(null, "UNKNOWN");
    }

    private Double calculateExactRMultiple(Trade trade) {
        if (trade == null || !trade.hasExactRiskBasis()) {
            return null;
        }

        String direction = trade.getDirection();
        String result = trade.getResult();

        double entry = trade.getEntryPrice();
        double stopLoss = trade.getRiskStopLoss();
        double exit = trade.getExitPrice();

        if (direction == null || direction.isBlank()) {
            return null;
        }

        if ("LOSS".equalsIgnoreCase(result)) {
            return -1.0;
        }

        if (entry <= 0 || stopLoss <= 0 || exit <= 0) {
            return null;
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
            return null;
        }

        if (risk <= 0) {
            return null;
        }

        return reward / risk;
    }

    private void ensureInitialStopLoss(Trade trade) {
        if (trade == null) {
            return;
        }
        if (trade.isImportedFromMt5()) {
            if (trade.getInitialStopLossConfirmed() == null) {
                trade.setInitialStopLossConfirmed(false);
            }
            return;
        }
        if ((trade.getInitialStopLoss() == null || trade.getInitialStopLoss() <= 0) && trade.getStopLoss() > 0) {
            trade.setInitialStopLoss(trade.getStopLoss());
        }
        if (trade.getInitialStopLoss() != null && trade.getInitialStopLoss() > 0 && trade.getInitialStopLossConfirmed() == null) {
            trade.setInitialStopLossConfirmed(true);
        }
    }

    private void replaceMistakes(Trade trade, User ownerUser, List<String> mistakeIds, String customMistakes) {
        tradeMistakeTagRepository.deleteByTradeId(trade.getId());

        List<MistakeTag> selectedTags = new ArrayList<>();

        if (mistakeIds != null && !mistakeIds.isEmpty()) {
            if (ownerUser != null && ownerUser.getId() != null && !ownerUser.getId().isBlank()) {
                selectedTags.addAll(mistakeTagRepository.findVisibleByIdIn(ownerUser.getId(), mistakeIds));
            } else {
                selectedTags.addAll(mistakeTagRepository.findAllById(mistakeIds));
            }
        }

        if (customMistakes != null && !customMistakes.isBlank()) {
            String[] parts = customMistakes.split(",");

            for (String part : parts) {
                String raw = part.trim();
                if (raw.isBlank()) {
                    continue;
                }

                MistakeTag tag = mistakeTagService.findOrCreateByName(raw, ownerUser);

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

    private List<Trade> filterTrades(List<Trade> trades, TradeFilterCriteria criteria) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }

        TradeFilterCriteria safeCriteria = criteria != null
                ? criteria
                : new TradeFilterCriteria(null, null, null, null, null, null, null, null);

        LocalDateTime fromValue = safeCriteria.from() != null ? safeCriteria.from().atStartOfDay() : null;
        LocalDateTime toValue = safeCriteria.to() != null ? safeCriteria.to().atTime(LocalTime.MAX) : null;
        if (fromValue != null && toValue != null && fromValue.isAfter(toValue)) {
            LocalDateTime temp = fromValue;
            fromValue = toValue;
            toValue = temp;
        }
        final LocalDateTime from = fromValue;
        final LocalDateTime to = toValue;

        List<Trade> filteredTrades = trades.stream()
                .filter(trade -> matchesContainsFilter(trade.getSymbol(), safeCriteria.symbol()))
                .filter(trade -> matchesExactFilter(trade.getSetupName(), safeCriteria.setup()))
                .filter(trade -> matchesExactFilter(trade.getSession(), safeCriteria.session()))
                .filter(trade -> matchesResultFilter(trade, safeCriteria.result()))
                .filter(trade -> matchesMistakeFilter(trade, safeCriteria.mistake()))
                .filter(trade -> matchesDateFilter(resolveTradeTimestamp(trade), from, to))
                .toList();

        if (!safeCriteria.aiReviewedOnly()) {
            return filteredTrades;
        }
        return retainAiReviewedTrades(filteredTrades);
    }

    private List<Trade> retainAiReviewedTrades(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }

        List<String> tradeIds = trades.stream()
                .map(Trade::getId)
                .filter(StringUtils::hasText)
                .toList();
        if (tradeIds.isEmpty()) {
            return List.of();
        }

        Set<String> reviewedTradeIds = tradeReviewRepository.findByTradeIdIn(tradeIds).stream()
                .filter(Objects::nonNull)
                .filter(review -> review.getTrade() != null && StringUtils.hasText(review.getTrade().getId()))
                .filter(review -> review.getQualityScore() != null)
                .map(review -> review.getTrade().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (reviewedTradeIds.isEmpty()) {
            return List.of();
        }

        return trades.stream()
                .filter(trade -> reviewedTradeIds.contains(trade.getId()))
                .toList();
    }

    private List<String> normalizeTradeIds(List<String> tradeIds) {
        if (tradeIds == null || tradeIds.isEmpty()) {
            return List.of();
        }

        return tradeIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private boolean matchesContainsFilter(String value, String filter) {
        if (!StringUtils.hasText(filter)) {
            return true;
        }
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.trim().toUpperCase().contains(filter.trim().toUpperCase());
    }

    private boolean matchesExactFilter(String value, String filter) {
        if (!StringUtils.hasText(filter) || "N/A".equalsIgnoreCase(filter.trim())) {
            return true;
        }
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.trim().equalsIgnoreCase(filter.trim());
    }

    private boolean matchesResultFilter(Trade trade, String filter) {
        if (!StringUtils.hasText(filter)) {
            return true;
        }
        return normalizeResultValue(trade != null ? trade.getResult() : null)
                .equals(normalizeResultValue(filter));
    }

    private boolean matchesMistakeFilter(Trade trade, String filter) {
        if (!StringUtils.hasText(filter)) {
            return true;
        }
        if (trade == null || trade.getMistakes() == null || trade.getMistakes().isEmpty()) {
            return false;
        }

        String normalizedFilter = filter.trim();
        return trade.getMistakes().stream()
                .filter(Objects::nonNull)
                .map(MistakeTag::getName)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(name -> name.equalsIgnoreCase(normalizedFilter));
    }

    private boolean matchesDateFilter(LocalDateTime timestamp, LocalDateTime from, LocalDateTime to) {
        if (from == null && to == null) {
            return true;
        }
        if (timestamp == null) {
            return false;
        }
        if (from != null && timestamp.isBefore(from)) {
            return false;
        }
        if (to != null && timestamp.isAfter(to)) {
            return false;
        }
        return true;
    }

    private LocalDateTime resolveTradeTimestamp(Trade trade) {
        if (trade == null) {
            return null;
        }
        if (trade.getEntryTime() != null) {
            return trade.getEntryTime();
        }
        if (trade.getTradeDate() != null) {
            return trade.getTradeDate();
        }
        return trade.getCreatedAt();
    }

    private String normalizeResultValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.contains("PARTIAL")) {
            return "PARTIAL";
        }
        if ("BE".equals(normalized) || "BREAKEVEN".equals(normalized)) {
            return "BE";
        }
        if ("WIN".equals(normalized)) {
            return "WIN";
        }
        if ("LOSS".equals(normalized)) {
            return "LOSS";
        }
        return normalized;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Setup resolveSetupForUser(Setup rawSetup, String userId) {
        String setupId = extractSetupId(rawSetup);
        return setupRepository.findByIdAndUserId(setupId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid setup selected"));
    }

    private Setup resolveSetupForAdmin(Setup rawSetup, String ownerUserId) {
        String setupId = extractSetupId(rawSetup);
        if (!StringUtils.hasText(ownerUserId)) {
            throw new IllegalStateException("Trade owner is required");
        }
        return setupRepository.findByIdAndUserId(setupId, ownerUserId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid setup selected"));
    }

    private String extractSetupId(Setup rawSetup) {
        if (rawSetup == null || rawSetup.getId() == null || rawSetup.getId().isBlank()) {
            throw new IllegalArgumentException("Setup is required");
        }
        return rawSetup.getId().trim();
    }

    private record RMultipleComputation(Double value, String source) {
    }
}
