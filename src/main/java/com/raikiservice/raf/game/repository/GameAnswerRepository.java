package com.raikiservice.raf.game.repository;

import java.util.List;

import com.raikiservice.raf.game.domain.GameAnswer;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GameAnswerRepository extends JpaRepository<GameAnswer, Long> {

    List<GameAnswer> findBySessionId(Long sessionId);
}
