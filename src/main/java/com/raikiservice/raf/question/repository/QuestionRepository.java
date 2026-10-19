package com.raikiservice.raf.question.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionStatus;

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

    Optional<Question> findByCategoryIdAndTextHash(Long categoryId, String textHash);

    boolean existsByCategoryIdAndTextHash(Long categoryId, String textHash);

    Page<Question> findByCategoryIdAndStatus(Long categoryId, QuestionStatus status,
            Pageable pageable);

    Page<Question> findByStatus(QuestionStatus status, Pageable pageable);

    long countByCategoryIdAndStatus(Long categoryId, QuestionStatus status);

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
