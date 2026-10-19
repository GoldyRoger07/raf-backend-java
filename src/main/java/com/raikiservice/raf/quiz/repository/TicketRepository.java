package com.raikiservice.raf.quiz.repository;

import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.quiz.domain.Ticket;
import com.raikiservice.raf.quiz.domain.TicketStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** Joueurs detenant au moins un ticket valable : destinataires des annonces du quiz. */
    @Query("select distinct t.user.id from Ticket t where t.quiz.id = :quizId "
            + "and t.status <> com.raikiservice.raf.quiz.domain.TicketStatus.REFUNDED")
    List<Long> findHolderIds(@Param("quizId") Long quizId);

    /** Tous les acheteurs, rembourses compris : destinataires de l'annonce d'annulation. */
    @Query("select distinct t.user.id from Ticket t where t.quiz.id = :quizId")
    List<Long> findBuyerIds(@Param("quizId") Long quizId);

    /**
     * Identifiants des tickets encore jouables, du plus ancien au plus recent.
     *
     * <p>Projection scalaire volontaire : aucune entite Ticket n'entre dans le
     * contexte de persistance, si bien que le verrou pris ensuite par
     * {@link #findByIdForUpdate} reste la premiere lecture de l'entite. Et on ne
     * verrouille jamais par une requete susceptible de ne rien trouver.
     */
    @Query("select t.id from Ticket t where t.quiz.id = :quizId and t.user.id = :userId "
            + "and t.status = com.raikiservice.raf.quiz.domain.TicketStatus.ACTIVE "
            + "order by t.purchasedAt asc, t.id asc")
    List<Long> findActiveTicketIds(@Param("quizId") Long quizId, @Param("userId") Long userId,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);
}
