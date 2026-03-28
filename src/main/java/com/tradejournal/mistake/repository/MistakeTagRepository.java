package com.tradejournal.mistake.repository;

import com.tradejournal.mistake.domain.MistakeTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MistakeTagRepository extends JpaRepository<MistakeTag, String> {

    List<MistakeTag> findByActiveTrueOrderByNameAsc();

    Optional<MistakeTag> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    Optional<MistakeTag> findByIdAndUserId(String id, String userId);

    Optional<MistakeTag> findByCodeIgnoreCaseAndUserId(String code, String userId);

    Optional<MistakeTag> findByCodeIgnoreCaseAndUserIsNull(String code);

    long countByUserId(String userId);

    void deleteByUserId(String userId);

    @Query("""
            select m
            from MistakeTag m
            where m.active = true
              and (m.user is null or m.user.id = :userId)
            order by lower(m.name), lower(m.code)
            """)
    List<MistakeTag> findVisibleActiveForUser(@Param("userId") String userId);

    @Query("""
            select m
            from MistakeTag m
            where m.user is null or m.user.id = :userId
            order by lower(m.name), lower(m.code)
            """)
    List<MistakeTag> findVisibleForUser(@Param("userId") String userId);

    @Query("""
            select m
            from MistakeTag m
            where m.id in :ids
              and (m.user is null or m.user.id = :userId)
            """)
    List<MistakeTag> findVisibleByIdIn(@Param("userId") String userId, @Param("ids") List<String> ids);
}
