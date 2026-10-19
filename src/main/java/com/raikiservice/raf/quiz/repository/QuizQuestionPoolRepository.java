package com.raikiservice.raf.quiz.repository;

import java.util.List;

import com.raikiservice.raf.quiz.domain.QuizQuestionPool;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizQuestionPoolRepository extends JpaRepository<QuizQuestionPool, Long> {

    long countByQuizId(Long quizId);

    /** Le vivier fige, reduit a ses identifiants : chaque joueur y tire sa partie. */
    @Query("select p.question.id from QuizQuestionPool p where p.quiz.id = :quizId")
    List<Long> findQuestionIds(@Param("quizId") Long quizId);
}
