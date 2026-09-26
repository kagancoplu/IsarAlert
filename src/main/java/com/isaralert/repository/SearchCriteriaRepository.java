package com.isaralert.repository;

import com.isaralert.model.SearchCriteria;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SearchCriteriaRepository extends JpaRepository<SearchCriteria, Long> {

    /**
     * Active criteria whose owner is also active — users paused via /stop are excluded.
     */
    @EntityGraph(attributePaths = {"user"})
    List<SearchCriteria> findAllByActiveTrueAndUserActiveTrue();

    List<SearchCriteria> findByUserId(Long userId);

    List<SearchCriteria> findByUserIdAndActiveTrue(Long userId);
}
