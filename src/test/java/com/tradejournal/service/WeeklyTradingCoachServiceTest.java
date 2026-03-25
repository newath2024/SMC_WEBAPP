package com.tradejournal.service;

import com.tradejournal.analytics.service.WeeklyCoachReportGenerator;
import com.tradejournal.setup.domain.Setup;
import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.domain.TradeReview;
import com.tradejournal.trade.repository.TradeMistakeTagRepository;
import com.tradejournal.trade.repository.TradeReviewRepository;
import com.tradejournal.trade.service.TradeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklyTradingCoachServiceTest {

    @Mock
    private TradeService tradeService;

    @Mock
    private TradeReviewRepository tradeReviewRepository;

    @Mock
    private TradeMistakeTagRepository tradeMistakeTagRepository;

    @InjectMocks
    private WeeklyCoachReportGenerator weeklyTradingCoachService;

    @Test
    void buildReportForUserHighlightsReviewGapsAndWeakPayoffCapture() {
        Trade previousWeekTrade = trade(
                "prev-1",
                "XAUUSD",
                "LOSS",
                1.4,
                -120.0,
                LocalDateTime.of(2026, 3, 8, 9, 0),
                "London Sweep"
        );
        Trade tradeOne = trade(
                "trade-1",
                "BTCUSD",
                "WIN",
                1.3,
                80.0,
                LocalDateTime.of(2026, 3, 12, 8, 47),
                "Classic FVG"
        );
        Trade tradeTwo = trade(
                "trade-2",
                "BTCUSD",
                "WIN",
                0.6,
                25.0,
                LocalDateTime.of(2026, 3, 12, 9, 10),
                "Classic FVG"
        );
        Trade tradeThree = trade(
                "trade-3",
                "BTCUSD",
                "WIN",
                2.4,
                110.0,
                LocalDateTime.of(2026, 3, 15, 16, 17),
                "Classic FVG"
        );

        TradeReview structuredReview = review(tradeOne, 90);
        structuredReview.setFollowedPlan(true);
        structuredReview.setHadConfirmation(true);
        structuredReview.setRespectedRisk(true);
        structuredReview.setAlignedHtfBias(true);
        structuredReview.setCorrectSession(true);
        structuredReview.setCorrectSetup(true);
        structuredReview.setCorrectPoi(true);
        structuredReview.setHadFomo(false);
        structuredReview.setEnteredBeforeNews(false);
        structuredReview.setWouldTakeAgain(true);

        TradeReview aiOnlyReview = review(tradeTwo, 75);
        aiOnlyReview.setAiSummary("Clean outcome, weak evidence.");

        TradeReview aiOnlyReviewTwo = review(tradeThree, 82);
        aiOnlyReviewTwo.setAiSummary("Good trade, limited manual review.");

        when(tradeService.findAllByUser("user-1")).thenReturn(List.of(previousWeekTrade, tradeOne, tradeTwo, tradeThree));
        when(tradeReviewRepository.findByTradeIdIn(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            List<TradeReview> reviews = new ArrayList<>();
            if (ids.contains("trade-1")) {
                reviews.add(structuredReview);
            }
            if (ids.contains("trade-2")) {
                reviews.add(aiOnlyReview);
            }
            if (ids.contains("trade-3")) {
                reviews.add(aiOnlyReviewTwo);
            }
            return reviews;
        });
        when(tradeMistakeTagRepository.findByTradeIdIn(anyList())).thenReturn(List.of());

        WeeklyCoachReportGenerator.WeeklyCoachReport report = weeklyTradingCoachService.buildReportForUser(
                "user-1",
                LocalDate.of(2026, 3, 18)
        );

        assertEquals(3, report.getTotalTrades());
        assertEquals(82, report.getAvgProcessScore());
        assertTrue(report.getSummary().contains("under-reviewing trades"));
        assertEquals("Incomplete self-review", report.getTopMistakes().get(0).getTitle());
        assertEquals("Weak payoff capture", report.getTopMistakes().get(1).getTitle());
        assertTrue(report.getDashboardSummary().contains("remains the main issue"));
        assertEquals("Incomplete self-review", report.getPrimaryIssue().getTitle());
        assertTrue(report.getDashboardImprovements().size() <= 3);
        assertTrue(report.getImprovements().stream().anyMatch(item -> item.contains("post-trade review")));
        assertTrue(report.getNextWeekPlan().stream().anyMatch(item -> item.contains("BTCUSD")));
    }

    @Test
    void buildReportForUserReturnsEmptyStateWhenWeekHasNoTrades() {
        Trade oldTrade = trade(
                "old-1",
                "XAUUSD",
                "LOSS",
                -1.0,
                -50.0,
                LocalDateTime.of(2026, 2, 20, 9, 0),
                "London Sweep"
        );

        when(tradeService.findAllByUser("user-1")).thenReturn(List.of(oldTrade));

        WeeklyCoachReportGenerator.WeeklyCoachReport report = weeklyTradingCoachService.buildReportForUser(
                "user-1",
                LocalDate.of(2026, 3, 18)
        );

        assertTrue(report.isEmpty());
        assertEquals(0, report.getTotalTrades());
        assertTrue(report.getSummary().contains("No trades were recorded"));
        assertEquals(1, report.getTopMistakes().size());
    }

    private Trade trade(
            String id,
            String symbol,
            String result,
            double rMultiple,
            double pnl,
            LocalDateTime entryTime,
            String setupName
    ) {
        Trade trade = new Trade();
        trade.setId(id);
        trade.setSymbol(symbol);
        trade.setResult(result);
        trade.setRMultiple(rMultiple);
        trade.setRMultipleSource("EXACT");
        trade.setPnl(pnl);
        trade.setEntryTime(entryTime);
        trade.setTradeDate(entryTime);
        trade.setSession("LONDON");
        trade.setSetup(setup(setupName));
        return trade;
    }

    private TradeReview review(Trade trade, int processScore) {
        TradeReview review = new TradeReview();
        review.setTrade(trade);
        review.setAiProcessScore(processScore);
        return review;
    }

    private Setup setup(String name) {
        Setup setup = new Setup();
        setup.setName(name);
        return setup;
    }
}
