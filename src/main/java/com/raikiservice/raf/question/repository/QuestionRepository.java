package com.raikiservice.raf.question.repository;

import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    @EntityGraph(attributePaths = "choices")
    Optional<Question> findWithChoicesById(Long id);

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
