package com.raikiservice.raf.quiz.repository;

import java.util.Optional;

import com.raikiservice.raf.quiz.domain.TopicVote;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicVoteRepository extends JpaRepository<TopicVote, Long> {

    Optional<TopicVote> findByQuizIdAndUserId(Long quizId, Long userId);

    long countByQuizId(Long quizId);
}
