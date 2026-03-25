package com.tradejournal.mistake.repository;

import com.tradejournal.mistake.domain.MistakeTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MistakeTagRepository extends JpaRepository<MistakeTag, String> {

    List<MistakeTag> findByActiveTrueOrderByNameAsc();

    Optional<MistakeTag> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);
}
