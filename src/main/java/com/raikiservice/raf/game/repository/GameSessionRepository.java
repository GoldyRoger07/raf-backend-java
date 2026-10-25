package com.raikiservice.raf.game.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.game.domain.GameSession;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.quiz.domain.PlayMode;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    /**
     * Verrouille la partie avant le submit : deux envois simultanes (double clic,
     * nouvel essai reseau) sont serialises, et le second retrouve le resultat du
     * premier. Comme partout : premiere lecture de la partie dans la transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GameSession s where s.id = :id")
    Optional<GameSession> findByIdForUpdate(@Param("id") Long id);

    /**
     * Lecture verrouillee (donc "courante") de la partie liee a un ticket : sous
     * REPEATABLE READ, une lecture simple resterait sur l'instantane du debut de
     * transaction et ne verrait pas une partie creee entre-temps par une requete
     * concurrente.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from GameSession s where s.ticket.id = :ticketId")
    Optional<GameSession> findByTicketIdForShare(@Param("ticketId") Long ticketId);

    Optional<GameSession> findFirstByQuizIdAndUserIdAndStatusAndServerDeadlineAtAfterOrderByStartedAtDesc(
            Long quizId, Long userId, GameSessionStatus status, Instant now);

    List<GameSession> findByQuizIdAndUserIdOrderByStartedAtDesc(Long quizId, Long userId);

    boolean existsByUserIdAndCategoryIdAndSoloDay(Long userId, Long categoryId, LocalDate soloDay);

    Optional<GameSession> findByEntryTransactionId(Long entryTransactionId);

    @Query("select s.categoryId from GameSession s where s.user.id = :userId "
            + "and s.soloDay = :day")
    List<Long> findSoloCategoryIds(@Param("userId") Long userId, @Param("day") LocalDate day);

    Page<GameSession> findByQuizIdOrderByStartedAtDesc(Long quizId, Pageable pageable);

    Page<GameSession> findByQuizIdAndFlaggedTrueOrderByStartedAtDesc(Long quizId, Pageable pageable);

    /**
     * Signalements encore actionnables, tous quiz confondus : parties validees et
     * signalees d'un quiz dont les gains ne sont pas encore verses.
     */
    @Query("select s from GameSession s where s.flagged = true "
            + "and s.status = com.raikiservice.raf.game.domain.GameSessionStatus.SUBMITTED "
            + "and s.quiz.status not in (com.raikiservice.raf.quiz.domain.QuizStatus.FINISHED, "
            + "com.raikiservice.raf.quiz.domain.QuizStatus.CANCELLED) "
            + "order by s.startedAt desc")
    Page<GameSession> findActionableFlagged(Pageable pageable);

    /** Parties encore ouvertes : le classement attend qu'elles soient closes. */
    long countByQuizIdAndStatus(Long quizId, GameSessionStatus status);

    long countByQuizIdAndStatusNot(Long quizId, GameSessionStatus status);

    long countByQuizIdAndWonTrue(Long quizId);

    /**
     * Candidats au classement, deja dans l'ordre final : score decroissant, puis
     * debut de partie croissant (departage), puis id pour un ordre total.
     */
    @Query("select s from GameSession s join fetch s.user "
            + "where s.quiz.id = :quizId "
            + "and s.status = com.raikiservice.raf.game.domain.GameSessionStatus.SUBMITTED "
            + "order by s.score desc, s.startedAt asc, s.id asc")
    List<GameSession> findRankable(@Param("quizId") Long quizId);

    /** Meilleur score deja atteint par le joueur sur ce quiz. */
    @Query("select max(s.score) from GameSession s where s.quiz.id = :quizId "
            + "and s.user.id = :userId "
            + "and s.status = com.raikiservice.raf.game.domain.GameSessionStatus.SUBMITTED")
    Optional<Integer> bestScore(@Param("quizId") Long quizId, @Param("userId") Long userId);

    /**
     * Une partie jamais soumise a temps ne compte pas : elle est close en masse.
     * Reserve au mode BATCH, ou rien n'est enregistre avant le submit.
     */
    @Modifying
    @Query("update GameSession s set s.status = com.raikiservice.raf.game.domain.GameSessionStatus.EXPIRED "
            + "where s.status = com.raikiservice.raf.game.domain.GameSessionStatus.IN_PROGRESS "
            + "and s.playMode = :mode and s.serverDeadlineAt < :now")
    int expireOverdue(@Param("mode") PlayMode mode, @Param("now") Instant now);

    /** Parties encore ouvertes au-dela de leur echeance, pour cloture une a une. */
    @Query("select s.id from GameSession s "
            + "where s.status = com.raikiservice.raf.game.domain.GameSessionStatus.IN_PROGRESS "
            + "and s.playMode = :mode and s.serverDeadlineAt < :now")
    List<Long> findOverdueIds(@Param("mode") PlayMode mode, @Param("now") Instant now);
}
