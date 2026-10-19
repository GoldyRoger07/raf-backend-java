package com.raikiservice.raf.quiz.repository;

import java.util.List;

import com.raikiservice.raf.quiz.domain.QuizReward;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRewardRepository extends JpaRepository<QuizReward, Long> {

    List<QuizReward> findByQuizIdOrderByRankFromAsc(Long quizId);

    void deleteByQuizId(Long quizId);
}
