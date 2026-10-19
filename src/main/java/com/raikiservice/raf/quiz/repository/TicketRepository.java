package com.raikiservice.raf.quiz.repository;

import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.quiz.domain.Ticket;
import com.raikiservice.raf.quiz.domain.TicketStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByIdempotencyKey(String idempotencyKey);

    Page<Ticket> findByUserIdOrderByPurchasedAtDesc(Long userId, Pageable pageable);

    List<Ticket> findByQuizIdAndUserId(Long quizId, Long userId);

    List<Ticket> findByQuizIdAndStatus(Long quizId, TicketStatus status);

    long countByQuizIdAndStatusNot(Long quizId, TicketStatus status);

    long countByQuizIdAndUserIdAndStatusNot(Long quizId, Long userId, TicketStatus status);

    /** Nombre de joueurs distincts : c'est lui qui est compare au seuil minimum. */
    @Query("select count(distinct t.user.id) from Ticket t "
            + "where t.quiz.id = :quizId and t.status <> com.raikiservice.raf.quiz.domain.TicketStatus.REFUNDED")
    long countDistinctPlayers(@Param("quizId") Long quizId);

    boolean existsByQuizIdAndUserIdAndStatusNot(Long quizId, Long userId, TicketStatus status);
}
