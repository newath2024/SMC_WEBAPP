package com.tradejournal.repository;

import com.tradejournal.entity.Trade;
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

    @EntityGraph(attributePaths = {"setup"})
    List<Trade> findByUserIdAndIdInOrderByEntryTimeDesc(String userId, List<String> ids);

    void deleteByUserId(String userId);

    boolean existsByUserIdAndMt5PositionId(String userId, String mt5PositionId);

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
            select count(t)
            from Trade t
            where t.user.id = :userId
              and lower(coalesce(t.note, '')) like lower(concat('Imported from MT5 history | Position #', :positionId, ' |%'))
            """)
    long countLegacyMt5ImportsByUserIdAndPositionId(@Param("userId") String userId, @Param("positionId") String positionId);

    @Query("""
            select t.setup.id as setupId,
                   count(t.id) as tradeCount,
                   sum(case when upper(coalesce(t.result, '')) = 'WIN' then 1 else 0 end) as winCount,
                   avg(case when upper(coalesce(t.rMultipleSource, 'UNKNOWN')) <> 'UNKNOWN' then t.rMultiple else null end) as averageR,
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
                   avg(case when upper(coalesce(t.rMultipleSource, 'UNKNOWN')) <> 'UNKNOWN' then t.rMultiple else null end) as averageR,
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
