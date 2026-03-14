package com.example.demo.repository;

import com.example.demo.entity.Trade;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TradeRepository extends JpaRepository<Trade, String> {

    long countByUserId(String userId);

    @EntityGraph(attributePaths = {"setup"})
    List<Trade> findByUserIdOrderByEntryTimeDesc(String userId);

    @EntityGraph(attributePaths = {"setup"})
    Optional<Trade> findByIdAndUserId(String id, String userId);

    boolean existsByUserIdAndEntryTimeAndExitTimeAndSymbolIgnoreCaseAndDirectionIgnoreCaseAndPositionSizeAndEntryPrice(
            String userId,
            java.time.LocalDateTime entryTime,
            java.time.LocalDateTime exitTime,
            String symbol,
            String direction,
            double positionSize,
            double entryPrice
    );

    @Query("""
            select t.setup.id as setupId,
                   count(t.id) as tradeCount,
                   sum(case when upper(coalesce(t.result, '')) = 'WIN' then 1 else 0 end) as winCount,
                   avg(t.rMultiple) as averageR,
                   sum(t.pnl) as totalPnl
            from Trade t
            where t.setup is not null and t.user.id = :userId
            group by t.setup.id
            """)
    List<SetupTradeMetricsRow> summarizeBySetupForUser(@Param("userId") String userId);

    @Query("""
            select t.setup.id as setupId,
                   count(t.id) as tradeCount,
                   sum(case when upper(coalesce(t.result, '')) = 'WIN' then 1 else 0 end) as winCount,
                   avg(t.rMultiple) as averageR,
                   sum(t.pnl) as totalPnl
            from Trade t
            where t.setup is not null
            group by t.setup.id
            """)
    List<SetupTradeMetricsRow> summarizeBySetupAllUsers();

    interface SetupTradeMetricsRow {
        String getSetupId();
        long getTradeCount();
        long getWinCount();
        Double getAverageR();
        Double getTotalPnl();
    }
}
