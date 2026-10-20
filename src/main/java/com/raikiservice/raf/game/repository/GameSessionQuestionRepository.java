package com.raikiservice.raf.game.repository;

import java.util.List;

import com.raikiservice.raf.game.domain.GameSessionQuestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GameSessionQuestionRepository extends JpaRepository<GameSessionQuestion, Long> {

    List<GameSessionQuestion> findBySessionIdOrderByPositionAsc(Long sessionId);

    int countBySessionId(Long sessionId);
}
