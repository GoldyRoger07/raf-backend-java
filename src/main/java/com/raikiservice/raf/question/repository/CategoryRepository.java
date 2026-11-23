package com.raikiservice.raf.question.repository;

import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.question.domain.Category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    Optional<Category> findByNameIgnoreCase(String name);

    boolean existsBySlug(String slug);

    List<Category> findByActiveTrueOrderByNameAsc();

    List<Category> findByParentIdOrderByNameAsc(Long parentId);

    /** Sujets d'entrainement actifs, par nom. */
    List<Category> findByActiveTrueAndPracticeEnabledTrueOrderByNameAsc();

    /**
     * Quiz non termines (brouillon compris) qui utilisent ce sujet, comme sujet choisi ou
     * comme sujet propose au vote.
     */
    @Query(value = "SELECT COUNT(DISTINCT q.id) FROM quizzes q "
            + "LEFT JOIN quiz_topics t ON t.quiz_id = q.id "
            + "WHERE q.status NOT IN ('FINISHED', 'CANCELLED') "
            + "AND (q.selected_category_id = :categoryId OR t.category_id = :categoryId)",
            nativeQuery = true)
    long countUnfinishedQuizzesUsing(@Param("categoryId") Long categoryId);
}
