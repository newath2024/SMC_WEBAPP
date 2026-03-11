package com.example.demo.repository;

import com.example.demo.entity.TradeMistakeTag;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<TradeMistakeTag> findByTradeId(String tradeId);

    List<TradeMistakeTag> findByTradeIdIn(List<String> tradeIds);

    void deleteByTradeId(String tradeId);

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
            where t.trade.user.id = :userId
            group by coalesce(nullif(trim(t.trade.session), ''), 'UNKNOWN')
            order by count(t.id) desc
            """)
    List<MistakeSessionRow> summarizeBySessionForUser(@Param("userId") String userId);

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
            where t.trade.user.id = :userId
            order by t.trade.entryTime desc
            """)
    List<RecentMistakeRow> findRecentMistakesForUser(@Param("userId") String userId);
}
