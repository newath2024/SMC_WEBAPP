package com.example.demo.repository;

import com.example.demo.entity.Setup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SetupRepository extends JpaRepository<Setup, String> {

    List<Setup> findByUserIdAndActiveTrueOrderByNameAsc(String userId);

    List<Setup> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Setup> findByUserIdOrderByActiveDescNameAsc(String userId);

    Optional<Setup> findByUserIdAndNameIgnoreCase(String userId, String name);

    Optional<Setup> findByIdAndUserId(String id, String userId);

    boolean existsByUserIdAndNameIgnoreCase(String userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(String userId, String name, String id);

    void deleteByUserId(String userId);
}
