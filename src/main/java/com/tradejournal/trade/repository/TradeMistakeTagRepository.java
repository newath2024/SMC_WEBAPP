package com.tradejournal.trade.repository;

import com.tradejournal.trade.domain.TradeMistakeTag;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TradeMistakeTagRepository extends JpaRepository<TradeMistakeTag, String> {

    interface MistakeUsageRow {
        String getMistakeTagId();
        long getUsageCount();
    }

    interface MistakeSessionRow {
        String getSession();
        long getUsageCount();
    }

    interface MistakeSymbolRow {
        String getSymbol();
        long getUsageCount();
    }

    interface RecentMistakeRow {
        String getTradeId();
        String getMistakeName();
        String getSession();
        String getSymbol();
        java.time.LocalDateTime getEntryTime();
    }

    @EntityGraph(attributePaths = {"trade", "mistakeTag"})
    List<TradeMistakeTag> findByTradeId(String tradeId);

    @EntityGraph(attributePaths = {"trade", "mistakeTag"})
    List<TradeMistakeTag> findByTradeIdIn(List<String> tradeIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TradeMistakeTag link where link.trade.id = :tradeId")
    void deleteByTradeId(@Param("tradeId") String tradeId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TradeMistakeTag link where link.trade.id in :tradeIds")
    void deleteByTradeIdIn(@Param("tradeIds") List<String> tradeIds);

    void deleteByMistakeTagId(String mistakeTagId);

    boolean existsByTradeIdAndMistakeTagId(String tradeId, String mistakeTagId);

    @Query("""
            select t.mistakeTag.id as mistakeTagId, count(t.id) as usageCount
            from TradeMistakeTag t
            group by t.mistakeTag.id
            """)
    List<MistakeUsageRow> countUsageByMistakeTag();

    @Query("""
            select t.mistakeTag.id as mistakeTagId, count(t.id) as usageCount
            from TradeMistakeTag t
            where t.trade.user.id = :userId
            group by t.mistakeTag.id
            """)
    List<MistakeUsageRow> countUsageByMistakeTagForUser(@Param("userId") String userId);

    @Query("""
            select coalesce(nullif(trim(t.trade.session), ''), 'UNKNOWN') as session, count(t.id) as usageCount
            from TradeMistakeTag t
            group by coalesce(nullif(trim(t.trade.session), ''), 'UNKNOWN')
            order by count(t.id) desc
            """)
    List<MistakeSessionRow> summarizeBySession();

    @Query("""
            select coalesce(nullif(trim(t.trade.session), ''), 'UNKNOWN') as session, count(t.id) as usageCount
            from TradeMistakeTag t
            where t.trade.user.id = :userId
            group by coalesce(nullif(trim(t.trade.session), ''), 'UNKNOWN')
            order by count(t.id) desc
            """)
    List<MistakeSessionRow> summarizeBySessionForUser(@Param("userId") String userId);

    @Query("""
            select coalesce(nullif(trim(t.trade.symbol), ''), 'UNKNOWN') as symbol, count(t.id) as usageCount
            from TradeMistakeTag t
            group by coalesce(nullif(trim(t.trade.symbol), ''), 'UNKNOWN')
            order by count(t.id) desc
            """)
    List<MistakeSymbolRow> summarizeBySymbol();

    @Query("""
            select coalesce(nullif(trim(t.trade.symbol), ''), 'UNKNOWN') as symbol, count(t.id) as usageCount
            from TradeMistakeTag t
            where t.trade.user.id = :userId
            group by coalesce(nullif(trim(t.trade.symbol), ''), 'UNKNOWN')
            order by count(t.id) desc
            """)
    List<MistakeSymbolRow> summarizeBySymbolForUser(@Param("userId") String userId);

    @Query("""
            select t.trade.id as tradeId,
                   t.mistakeTag.name as mistakeName,
                   t.trade.session as session,
                   t.trade.symbol as symbol,
                   t.trade.entryTime as entryTime
            from TradeMistakeTag t
            order by t.trade.entryTime desc
            """)
    List<RecentMistakeRow> findRecentMistakes();

    @Query("""
            select t.trade.id as tradeId,
                   t.mistakeTag.name as mistakeName,
                   t.trade.session as session,
                   t.trade.symbol as symbol,
                   t.trade.entryTime as entryTime
            from TradeMistakeTag t
            where t.trade.user.id = :userId
            order by t.trade.entryTime desc
            """)
    List<RecentMistakeRow> findRecentMistakesForUser(@Param("userId") String userId);
}
