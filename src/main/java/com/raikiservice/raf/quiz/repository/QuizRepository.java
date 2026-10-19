package com.raikiservice.raf.quiz.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    Optional<Quiz> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<Quiz> findByStatusOrderByCreatedAtDesc(QuizStatus status, Pageable pageable);

    Page<Quiz> findByStatusInOrderByCreatedAtDesc(List<QuizStatus> statuses, Pageable pageable);

    /**
     * Verrouille le quiz avant toute operation qui touche a ses compteurs
     * (achat de ticket, franchissement du seuil).
     *
     * <p>Comme pour le portefeuille : cette lecture doit etre la PREMIERE lecture
     * du quiz dans la transaction, sinon Hibernate sert une copie perimee depuis
     * son cache de premier niveau et le verrou ne protege plus rien.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quiz q where q.id = :id")
    Optional<Quiz> findByIdForUpdate(@Param("id") Long id);

    @Query("select q from Quiz q where q.status = :status and q.scheduledStartAt <= :now")
    List<Quiz> findDueToStart(@Param("status") QuizStatus status, @Param("now") Instant now);

    @Query("select q from Quiz q where q.status = :status and q.endsAt <= :now")
    List<Quiz> findDueToClose(@Param("status") QuizStatus status, @Param("now") Instant now);
}
