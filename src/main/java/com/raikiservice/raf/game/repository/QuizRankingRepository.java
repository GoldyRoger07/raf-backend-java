package com.raikiservice.raf.game.repository;

import java.util.Optional;

import com.raikiservice.raf.game.domain.QuizRanking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRankingRepository extends JpaRepository<QuizRanking, Long> {

    @EntityGraph(attributePaths = "user")
    Page<QuizRanking> findByQuizIdOrderByRankAsc(Long quizId, Pageable pageable);

    Optional<QuizRanking> findByQuizIdAndUserId(Long quizId, Long userId);

    boolean existsByQuizId(Long quizId);
}
