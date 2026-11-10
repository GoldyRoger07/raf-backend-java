package com.raikiservice.raf.game.repository;

import java.util.Optional;

import com.raikiservice.raf.game.domain.QuizRanking;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRankingRepository extends JpaRepository<QuizRanking, Long> {

    @EntityGraph(attributePaths = "user")
    Page<QuizRanking> findByQuizIdOrderByRankAsc(Long quizId, Pageable pageable);

    Optional<QuizRanking> findByQuizIdAndUserId(Long quizId, Long userId);

    /**
     * Classement vu du back-office : recherche (nom, email ; {@code term} deja entoure de
     * {@code %}, {@code phone} de meme) et filtre « avec ou sans gain », facultatifs.
     * Ordre : celui du Pageable (alias u pour le joueur).
     */
    @Query(value = "select r from QuizRanking r join fetch r.user u where r.quiz.id = :quizId "
            + "and (:rewarded is null or (:rewarded = true and r.rewardHtg > 0) or (:rewarded = false and r.rewardHtg = 0)) "
            + "and (:term is null or lower(concat(coalesce(u.firstName, ''), ' ', coalesce(u.lastName, ''))) like :term "
            + "  or lower(u.email) like :term or (:phone is not null and u.phone like :phone))",
            countQuery = "select count(r) from QuizRanking r join r.user u where r.quiz.id = :quizId "
            + "and (:rewarded is null or (:rewarded = true and r.rewardHtg > 0) or (:rewarded = false and r.rewardHtg = 0)) "
            + "and (:term is null or lower(concat(coalesce(u.firstName, ''), ' ', coalesce(u.lastName, ''))) like :term "
            + "  or lower(u.email) like :term or (:phone is not null and u.phone like :phone))")
    Page<QuizRanking> adminSearch(@Param("quizId") Long quizId, @Param("rewarded") Boolean rewarded,
            @Param("term") String term, @Param("phone") String phone, Pageable pageable);

    boolean existsByQuizId(Long quizId);
}
