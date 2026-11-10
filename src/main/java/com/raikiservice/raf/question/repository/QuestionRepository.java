package com.raikiservice.raf.question.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionSource;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    @EntityGraph(attributePaths = "choices")
    Optional<Question> findWithChoicesById(Long id);

    @EntityGraph(attributePaths = "choices")
    List<Question> findWithChoicesByIdIn(Collection<Long> ids);

    /** Export CSV d'une categorie : questions et propositions, dans l'ordre de creation. */
    @EntityGraph(attributePaths = "choices")
    List<Question> findWithChoicesByCategoryIdOrderByIdAsc(Long categoryId);

    @EntityGraph(attributePaths = "choices")
    List<Question> findWithChoicesByCategoryIdAndStatusOrderByIdAsc(Long categoryId,
            QuestionStatus status);

    /**
     * Compteurs d'usage, alimentes par le jeu. Mise a jour en masse plutot que par
     * entite : l'increment est fait par la base, donc deux parties simultanees
     * sur la meme question ne s'ecrasent pas.
     */
    @Modifying
    @Query("update Question q set q.timesServed = q.timesServed + 1 where q.id in :ids")
    int incrementTimesServed(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query("update Question q set q.timesCorrect = q.timesCorrect + 1 where q.id in :ids")
    int incrementTimesCorrect(@Param("ids") Collection<Long> ids);

    boolean existsByCategoryIdAndTextHash(Long categoryId, String textHash);

    Page<Question> findByCategoryIdAndStatus(Long categoryId, QuestionStatus status,
            Pageable pageable);

    /**
     * Liste du back-office : chaque critere est facultatif et se cumule aux autres.
     * {@code term} arrive deja en minuscules et entoure de {@code %} ; {@code usage} vaut
     * NEVER_SERVED, SERVED ou SUSPICIOUS (voir QuestionFilter.Usage).
     */
    @Query("select q from Question q where (:status is null or q.status = :status) "
            + "and (:categoryId is null or q.category.id = :categoryId) "
            + "and (:source is null or q.source = :source) "
            + "and (:difficulty is null or q.difficulty = :difficulty) "
            + "and (:type is null or q.type = :type) "
            + "and (:term is null or lower(q.text) like :term) "
            + "and (:usage is null "
            + "  or (:usage = 'NEVER_SERVED' and q.timesServed = 0) "
            + "  or (:usage = 'SERVED' and q.timesServed > 0) "
            + "  or (:usage = 'SUSPICIOUS' and q.timesServed >= :minServed "
            + "      and (cast(q.timesCorrect as double) / q.timesServed) >= :minRate))")
    Page<Question> filter(@Param("status") QuestionStatus status, @Param("categoryId") Long categoryId,
            @Param("source") QuestionSource source, @Param("difficulty") Difficulty difficulty,
            @Param("type") QuestionType type, @Param("term") String term, @Param("usage") String usage,
            @Param("minServed") int minServed, @Param("minRate") double minRate, Pageable pageable);

    /** Enonces les plus recents d'une categorie, tous statuts : a ne pas reproduire. */
    @Query("select q.text from Question q where q.category.id = :categoryId order by q.id desc")
    List<String> recentTexts(@Param("categoryId") Long categoryId, Pageable pageable);

    long countByCategoryIdAndStatus(Long categoryId, QuestionStatus status);

    // Voisins dans l'ordre de la liste du back-office (createdAt desc), l'id departageant
    // les questions creees au meme instant (import CSV).

    /** Question plus recente la plus proche : celle affichee juste avant. */
    @Query("select q.id from Question q where q.category.id = :categoryId and q.status = :status "
            + "and (q.createdAt > :createdAt or (q.createdAt = :createdAt and q.id > :id)) "
            + "order by q.createdAt asc, q.id asc")
    List<Long> newerIds(@Param("categoryId") Long categoryId, @Param("status") QuestionStatus status,
            @Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);

    /** Question plus ancienne la plus proche : celle affichee juste apres. */
    @Query("select q.id from Question q where q.category.id = :categoryId and q.status = :status "
            + "and (q.createdAt < :createdAt or (q.createdAt = :createdAt and q.id < :id)) "
            + "order by q.createdAt desc, q.id desc")
    List<Long> olderIds(@Param("categoryId") Long categoryId, @Param("status") QuestionStatus status,
            @Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);

    @Query("select count(q) from Question q where q.category.id = :categoryId and q.status = :status "
            + "and (q.createdAt > :createdAt or (q.createdAt = :createdAt and q.id > :id))")
    long countNewer(@Param("categoryId") Long categoryId, @Param("status") QuestionStatus status,
            @Param("createdAt") Instant createdAt, @Param("id") Long id);

    /** Compteurs d'usage d'une question : sa difficulte reelle, vue par les joueurs. */
    interface UsageStats {
        Difficulty getDifficulty();

        int getTimesServed();

        int getTimesCorrect();
    }

    @Query("select q.difficulty as difficulty, q.timesServed as timesServed, "
            + "q.timesCorrect as timesCorrect from Question q "
            + "where q.category.id = :categoryId and q.status = :status")
    List<UsageStats> usageStats(@Param("categoryId") Long categoryId,
            @Param("status") QuestionStatus status);

    long countByCategoryIdAndStatusAndDifficulty(Long categoryId, QuestionStatus status,
            Difficulty difficulty);

    /**
     * Recherche plein texte simple sur l'enonce, pour le back-office.
     *
     * <p>Suffisant pour une banque de quelques milliers de questions. Au-dela, il
     * faudra un index FULLTEXT.
     */
    @Query("select q from Question q where lower(q.text) like lower(concat('%', :term, '%'))")
    Page<Question> search(@Param("term") String term, Pageable pageable);

    /** Questions les plus servies avec un taux de reussite anormal : signal de fuite. */
    @Query("select q from Question q where q.timesServed >= :minServed "
            + "and (cast(q.timesCorrect as double) / q.timesServed) >= :minRate "
            + "order by q.timesServed desc")
    List<Question> findSuspiciouslyEasy(@Param("minServed") int minServed,
            @Param("minRate") double minRate, Pageable pageable);
}
