package com.raikiservice.raf.quiz.repository;

import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    Optional<Quiz> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<Quiz> findByStatusOrderByCreatedAtDesc(QuizStatus status, Pageable pageable);

    /**
     * Liste du back-office : statut obligatoire, le reste facultatif et cumulable.
     * {@code term} arrive deja en minuscules et entoure de {@code %}. L'alias {@code c}
     * (sujet arrete) sert aussi au tri par sujet.
     */
    @Query("select z from Quiz z left join z.selectedCategory c where z.status = :status "
            + "and (:mode is null or z.gameMode = :mode) "
            + "and (:term is null or lower(z.title) like :term or lower(z.themeLabel) like :term) "
            + "and (:categoryId is null or c.id = :categoryId) "
            + "and (:free is null or (:free = true and z.ticketPriceCredits = 0) "
            + "  or (:free = false and z.ticketPriceCredits > 0)) "
            + "and (:test is null or z.test = :test)")
    Page<Quiz> adminList(@Param("status") QuizStatus status, @Param("mode") GameMode mode,
            @Param("term") String term, @Param("categoryId") Long categoryId,
            @Param("free") Boolean free, @Param("test") Boolean test, Pageable pageable);

    Page<Quiz> findByStatusInOrderByCreatedAtDesc(List<QuizStatus> statuses, Pageable pageable);

    Page<Quiz> findByGameModeAndStatusOrderByCreatedAtDesc(GameMode mode, QuizStatus status,
            Pageable pageable);

    Page<Quiz> findByGameModeAndStatusInOrderByCreatedAtDesc(GameMode mode,
            List<QuizStatus> statuses, Pageable pageable);

    Page<Quiz> findByGameModeAndStatusInAndTestFalseOrderByCreatedAtDesc(GameMode mode,
            Collection<QuizStatus> statuses, Pageable pageable);

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

    /**
     * Verrou partage pris au lancement d'une partie : plusieurs parties peuvent
     * demarrer en parallele, mais la cloture (verrou exclusif) attend qu'elles
     * soient enregistrees, et une partie ne demarre jamais sur un quiz deja clos.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select q from Quiz q where q.id = :id")
    Optional<Quiz> findByIdForShare(@Param("id") Long id);

    /**
     * Seuil de cloture du quiz s'il tourne encore. Projection scalaire : le quiz
     * n'entre pas dans le contexte de persistance, et le verrou pris ensuite par
     * {@code QuizService#triggerClosing} reste la premiere lecture de l'entite.
     */
    @Query("select q.minScoreThreshold from Quiz q where q.id = :id and q.status = :status")
    Optional<Integer> findMinScoreThreshold(@Param("id") Long id,
            @Param("status") QuizStatus status);

    @Query("select q.id from Quiz q where q.status = :status")
    List<Long> findIdsByStatus(@Param("status") QuizStatus status);

    @Query("select q from Quiz q where q.status = :status and q.scheduledStartAt <= :now")
    List<Quiz> findDueToStart(@Param("status") QuizStatus status, @Param("now") Instant now);

    @Query("select q from Quiz q where q.status = :status and q.endsAt <= :now")
    List<Quiz> findDueToClose(@Param("status") QuizStatus status, @Param("now") Instant now);

    /** Projection scalaire : savoir si le quiz est de test sans le charger. */
    @Query("select q.test from Quiz q where q.id = :id")
    Optional<Boolean> findTestFlag(@Param("id") Long id);

    /**
     * Ferme un quiz solo ouvert qui se ferme au premier sans-faute. Mise a jour
     * conditionnelle et atomique : elle verrouille la ligne et ne depend pas de la
     * copie du quiz deja chargee dans la transaction (voir findByIdForUpdate).
     * Deux sans-faute simultanes : un seul ferme le quiz (1 ligne), l'autre 0.
     *
     * <p>Sans vider le contexte de persistance : la partie en cours y reste geree.
     * Le quiz qui s'y trouve peut garder l'ancien statut, mais il n'est pas
     * modifie ensuite, donc pas reecrit.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Quiz q set q.status = com.raikiservice.raf.quiz.domain.QuizStatus.FINISHED, "
            + "q.endsAt = :now, q.finishedAt = :now "
            + "where q.id = :id and q.closeOnPerfect = true "
            + "and q.gameMode = com.raikiservice.raf.quiz.domain.GameMode.SOLO "
            + "and q.status = com.raikiservice.raf.quiz.domain.QuizStatus.RUNNING")
    int closeSoloOnPerfect(@Param("id") Long id, @Param("now") Instant now);
}
