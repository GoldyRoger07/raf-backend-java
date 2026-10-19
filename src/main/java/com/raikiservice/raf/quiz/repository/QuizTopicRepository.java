package com.raikiservice.raf.quiz.repository;

import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.quiz.domain.QuizTopic;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizTopicRepository extends JpaRepository<QuizTopic, Long> {

    List<QuizTopic> findByQuizIdOrderByVoteCountDescIdAsc(Long quizId);

    long countByQuizId(Long quizId);

    /**
     * Verrou pris avant d'enregistrer un vote. Sans lui, deux votes simultanes
     * s'interbloquent : l'insertion du vote pose un verrou partage sur le sujet
     * (cle etrangere), puis l'increment exige un verrou exclusif sur la meme ligne.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from QuizTopic t where t.id = :id")
    Optional<QuizTopic> findByIdForUpdate(@Param("id") Long id);

    /**
     * Increment fait par la base : lire le compteur puis ecrire lu + 1 perdrait
     * un vote sur deux quand deux joueurs votent au meme instant pour le meme sujet.
     */
    @Modifying
    @Query("update QuizTopic t set t.voteCount = t.voteCount + 1 where t.id = :id")
    int incrementVoteCount(@Param("id") Long id);
}
