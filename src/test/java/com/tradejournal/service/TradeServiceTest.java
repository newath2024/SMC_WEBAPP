package com.tradejournal.service;

import com.tradejournal.auth.domain.User;
import com.tradejournal.mistake.domain.MistakeTag;
import com.tradejournal.setup.domain.Setup;
import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.domain.TradeMistakeTag;
import com.tradejournal.trade.domain.TradeReview;
import com.tradejournal.trade.dto.TradeFilterCriteria;
import com.tradejournal.mistake.repository.MistakeTagRepository;
import com.tradejournal.mistake.service.MistakeTagService;
import com.tradejournal.setup.repository.SetupRepository;
import com.tradejournal.trade.repository.TradeMistakeTagRepository;
import com.tradejournal.trade.repository.TradeRepository;
import com.tradejournal.trade.repository.TradeReviewRepository;
import com.tradejournal.trade.service.PnLCalculator;
import com.tradejournal.trade.service.TradeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    @Mock
    private TradeRepository repo;

    @Mock
    private PnLCalculator pnlCalculator;

    @Mock
    private TradeMistakeTagRepository tradeMistakeTagRepository;

    @Mock
    private MistakeTagRepository mistakeTagRepository;

    @Mock
    private SetupRepository setupRepository;

    @Mock
    private MistakeTagService mistakeTagService;

    @Mock
    private TradeReviewRepository tradeReviewRepository;

    @InjectMocks
    private TradeService tradeService;

    @Test
    void findFilteredTradeIdsMatchesCurrentTradeFilters() {
        Trade matchingTrade = trade(
                "trade-1",
                "XAUUSD",
                "BE and take partial",
                setup("London Sweep"),
                LocalDateTime.of(2026, 3, 10, 8, 15)
        );
        Trade otherTrade = trade(
                "trade-2",
                "BTCUSD",
                "WIN",
                setup("Asia Fade"),
                LocalDateTime.of(2026, 3, 10, 2, 45)
        );

        when(repo.findByUserIdOrderByEntryTimeDesc("user-1")).thenReturn(List.of(matchingTrade, otherTrade));
        when(tradeMistakeTagRepository.findByTradeId("trade-1")).thenReturn(List.of(link(matchingTrade, mistake("mistake-1", "Execution"))));
        when(tradeMistakeTagRepository.findByTradeId("trade-2")).thenReturn(List.of(link(otherTrade, mistake("mistake-2", "FOMO"))));

        TradeFilterCriteria criteria = new TradeFilterCriteria(
                null,
                "London Sweep",
                "LONDON",
                "xau",
                "PARTIAL",
                "Execution",
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31)
        );

        assertEquals(List.of("trade-1"), tradeService.findFilteredTradeIdsForUser("user-1", criteria));
    }

    @Test
    void findFilteredTradeIdsRespectsAiReviewedView() {
        Trade reviewedTrade = trade(
                "trade-1",
                "XAUUSD",
                "WIN",
                setup("Momentum"),
                LocalDateTime.of(2026, 3, 11, 9, 0)
        );
        Trade unreviewedTrade = trade(
                "trade-2",
                "BTCUSD",
                "LOSS",
                setup("Momentum"),
                LocalDateTime.of(2026, 3, 12, 9, 0)
        );
        TradeReview review = new TradeReview();
        review.setTrade(reviewedTrade);
        review.setQualityScore(82);

        when(repo.findByUserIdOrderByEntryTimeDesc("user-1")).thenReturn(List.of(reviewedTrade, unreviewedTrade));
        when(tradeMistakeTagRepository.findByTradeId("trade-1")).thenReturn(List.of());
        when(tradeMistakeTagRepository.findByTradeId("trade-2")).thenReturn(List.of());
        when(tradeReviewRepository.findByTradeIdIn(List.of("trade-1", "trade-2"))).thenReturn(List.of(review));

        TradeFilterCriteria criteria = new TradeFilterCriteria(
                "ai-reviewed",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertEquals(List.of("trade-1"), tradeService.findFilteredTradeIdsForUser("user-1", criteria));
    }

    @Test
    void deleteForUserIdsDeletesOnlyOwnedTrades() {
        Trade ownedTrade = trade(
                "trade-1",
                "XAUUSD",
                "WIN",
                setup("Momentum"),
                LocalDateTime.of(2026, 3, 11, 9, 0)
        );

        when(repo.findByUserIdAndIdInOrderByEntryTimeDesc("user-1", List.of("trade-1", "trade-2"))).thenReturn(List.of(ownedTrade));
        when(repo.findByUserIdOrderByEntryTimeDesc("user-1")).thenReturn(List.of());

        assertEquals(1, tradeService.deleteForUserIds(List.of("trade-1", "trade-2", "trade-1"), "user-1"));

        verify(tradeReviewRepository).deleteByTradeIdIn(List.of("trade-1"));
        verify(tradeMistakeTagRepository).deleteByTradeIdIn(List.of("trade-1"));
        verify(repo).deleteAllByIdInBatch(List.of("trade-1"));
    }

    @Test
    void updateForAdminRejectsSetupOwnedByAnotherUser() {
        User owner = new User();
        owner.setId("owner-1");

        Trade existingTrade = trade(
                "trade-1",
                "XAUUSD",
                "WIN",
                setup("Owner Setup"),
                LocalDateTime.of(2026, 3, 12, 9, 0)
        );
        existingTrade.setUser(owner);

        Trade formTrade = new Trade();
        Setup submittedSetup = new Setup();
        submittedSetup.setId("foreign-setup");
        formTrade.setSetup(submittedSetup);

        when(repo.findById("trade-1")).thenReturn(Optional.of(existingTrade));
        when(tradeMistakeTagRepository.findByTradeId("trade-1")).thenReturn(List.of());
        when(setupRepository.findByIdAndUserId("foreign-setup", "owner-1")).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> tradeService.updateForAdmin("trade-1", formTrade, List.of(), null)
        );

        assertEquals("Invalid setup selected", ex.getMessage());
        verify(setupRepository).findByIdAndUserId("foreign-setup", "owner-1");
        verify(repo, never()).save(any(Trade.class));
    }

    private Trade trade(String id, String symbol, String result, Setup setup, LocalDateTime entryTime) {
        Trade trade = new Trade();
        trade.setId(id);
        trade.setSymbol(symbol);
        trade.setResult(result);
        trade.setSetup(setup);
        trade.setEntryTime(entryTime);
        trade.setTradeDate(entryTime);
        trade.setPnl(150.0);
        trade.setRMultiple(1.5);
        trade.setRMultipleSource("EXACT");
        return trade;
    }

    private Setup setup(String name) {
        Setup setup = new Setup();
        setup.setName(name);
        return setup;
    }

    private MistakeTag mistake(String id, String name) {
        MistakeTag mistake = new MistakeTag();
        mistake.setId(id);
        mistake.setName(name);
        return mistake;
    }

    private TradeMistakeTag link(Trade trade, MistakeTag mistake) {
        TradeMistakeTag link = new TradeMistakeTag();
        link.setTrade(trade);
        link.setMistakeTag(mistake);
        return link;
    }
}
