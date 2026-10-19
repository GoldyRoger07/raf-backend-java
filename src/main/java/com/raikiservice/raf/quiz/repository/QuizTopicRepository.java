package com.raikiservice.raf.quiz.repository;

import java.util.List;

import com.raikiservice.raf.quiz.domain.QuizTopic;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizTopicRepository extends JpaRepository<QuizTopic, Long> {

    List<QuizTopic> findByQuizIdOrderByVoteCountDescIdAsc(Long quizId);

    long countByQuizId(Long quizId);
}
