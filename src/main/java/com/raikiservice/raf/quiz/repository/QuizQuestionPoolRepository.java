package com.raikiservice.raf.quiz.repository;

import com.raikiservice.raf.quiz.domain.QuizQuestionPool;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizQuestionPoolRepository extends JpaRepository<QuizQuestionPool, Long> {

    long countByQuizId(Long quizId);
}
