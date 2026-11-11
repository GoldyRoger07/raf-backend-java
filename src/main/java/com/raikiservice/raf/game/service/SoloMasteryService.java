package com.raikiservice.raf.game.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.quiz.domain.DifficultyMix;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Questions deja jouees en solo, par joueur et par sujet (table
 * {@code solo_mastered_questions}, nom historique : on n'y inscrivait d'abord que
 * les bonnes reponses).
 *
 * <p>Le tirage solo ecarte ces questions. Quand le vivier ne compte plus assez de
 * questions jamais jouees pour une partie complete, la liste est pleine : on en
 * retire les plus anciennes, juste ce qu'il faut, et elles sont resservies.
 *
 * <p>Sont inscrites toutes les questions des parties terminees (SUBMITTED), bien
 * repondues, ratees ou sans reponse, comptes de test compris : ni parties
 * invalidees, ni mode GROUP.
 */
@Service
@RequiredArgsConstructor
public class SoloMasteryService {

    private final JdbcTemplate jdbc;

    /** Questions jouees par sujet, pour la fiche d'un joueur au back-office. */
    public record SubjectCount(Long categoryId, String categoryName, long masteredCount) {
    }

    /**
     * Tire {@code count} questions du vivier pour le joueur : d'abord celles qu'il
     * n'a jamais jouees sur ce sujet, au hasard ; s'il n'y en a pas assez, les
     * plus anciennement jouees sont retirees de sa liste et completent la partie.
     * L'ordre renvoye est aleatoire.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> draw(Long userId, Long categoryId, List<Long> pool, int count, Random random) {
        // Plus anciennes d'abord : c'est l'ordre de purge.
        List<Long> mastered = jdbc.queryForList(
                "SELECT question_id FROM solo_mastered_questions "
                        + "WHERE user_id = ? AND category_id = ? ORDER BY mastered_at, id",
                Long.class, userId, categoryId);

        Set<Long> poolSet = new LinkedHashSet<>(pool);
        Set<Long> masteredSet = new HashSet<>(mastered);

        List<Long> fresh = new ArrayList<>();
        for (Long id : poolSet) {
            if (!masteredSet.contains(id)) {
                fresh.add(id);
            }
        }
        Collections.shuffle(fresh, random);

        List<Long> picked = new ArrayList<>(fresh.subList(0, Math.min(count, fresh.size())));
        int missing = count - picked.size();
        if (missing > 0) {
            // Liste pleine pour ce vivier : on libere les plus anciennes du vivier.
            // Les questions jouees hors de ce vivier (autre quiz du sujet, question
            // archivee) ne genent pas ce tirage et restent en place.
            List<Long> evicted = mastered.stream()
                    .filter(poolSet::contains)
                    .limit(missing)
                    .toList();
            remove(userId, categoryId, evicted);
            picked.addAll(evicted);
            Collections.shuffle(picked, random);
        }
        return picked;
    }

    /**
     * Tirage solo reparti par difficulte : {@code targets} donne le nombre de questions
     * voulu par niveau, {@code pool} le vivier par niveau. Pour chaque niveau :
     * <ol>
     *   <li>les questions jamais jouees par le joueur, au hasard ;</li>
     *   <li>s'il en manque, ses questions jouees les plus anciennes du meme niveau, retirees de
     *       sa liste et resservies ;</li>
     *   <li>s'il en manque encore (niveau trop petit dans le vivier), un niveau de repli
     *       ({@link DifficultyMix#fallbacks}) complete, jamais jouees d'abord.</li>
     * </ol>
     * La partie n'est plus courte que si le vivier entier l'est. L'ordre renvoye est
     * aleatoire : les niveaux sont melanges.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> drawMixed(Long userId, Long categoryId, Map<Difficulty, List<Long>> pool,
            Map<Difficulty, Integer> targets, Random random) {
        Set<Long> masteredSet = new HashSet<>();
        Map<Difficulty, Deque<Long>> fresh = new EnumMap<>(Difficulty.class);
        Map<Difficulty, Deque<Long>> oldest = new EnumMap<>(Difficulty.class);
        Map<Long, Difficulty> levelOf = new HashMap<>();
        for (Difficulty level : Difficulty.values()) {
            fresh.put(level, new ArrayDeque<>());
            oldest.put(level, new ArrayDeque<>());
            for (Long id : pool.getOrDefault(level, List.of())) {
                levelOf.put(id, level);
            }
        }

        // Plus anciennes d'abord : c'est l'ordre de purge, niveau par niveau.
        for (Long id : jdbc.queryForList(
                "SELECT question_id FROM solo_mastered_questions "
                        + "WHERE user_id = ? AND category_id = ? ORDER BY mastered_at, id",
                Long.class, userId, categoryId)) {
            masteredSet.add(id);
            Difficulty level = levelOf.get(id);
            if (level != null) {
                oldest.get(level).add(id);
            }
        }
        for (Difficulty level : Difficulty.values()) {
            List<Long> candidates = new ArrayList<>();
            for (Long id : new LinkedHashSet<>(pool.getOrDefault(level, List.of()))) {
                if (!masteredSet.contains(id)) {
                    candidates.add(id);
                }
            }
            Collections.shuffle(candidates, random);
            fresh.get(level).addAll(candidates);
        }

        List<Long> picked = new ArrayList<>();
        List<Long> evicted = new ArrayList<>();
        Map<Difficulty, Integer> missing = new EnumMap<>(Difficulty.class);
        for (Difficulty level : Difficulty.values()) {
            int want = targets.getOrDefault(level, 0);
            want -= take(fresh.get(level), want, picked);
            want -= take(oldest.get(level), want, evicted);
            missing.put(level, want);
        }
        for (Difficulty level : Difficulty.values()) {
            int want = missing.get(level);
            for (Difficulty fallback : DifficultyMix.fallbacks(level)) {
                want -= take(fresh.get(fallback), want, picked);
                want -= take(oldest.get(fallback), want, evicted);
            }
        }

        remove(userId, categoryId, evicted);
        picked.addAll(evicted);
        Collections.shuffle(picked, random);
        return picked;
    }

    /** Deplace jusqu'a {@code count} elements de la tete de {@code from} vers {@code to}. */
    private static int take(Deque<Long> from, int count, List<Long> to) {
        int moved = 0;
        while (moved < count && !from.isEmpty()) {
            to.add(from.poll());
            moved++;
        }
        return moved;
    }

    /**
     * Inscrit les questions d'une partie terminee. Une question deja dans la
     * liste voit sa date remise a jour : elle repasse en fin de file.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long userId, Long categoryId, Long sessionId, Collection<Long> questionIds,
            Instant at) {
        if (questionIds.isEmpty()) {
            return;
        }
        Timestamp when = Timestamp.from(at);
        List<Object[]> rows = questionIds.stream()
                .distinct()
                .map(questionId -> new Object[] {userId, categoryId, questionId, sessionId, when})
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO solo_mastered_questions "
                        + "(user_id, category_id, question_id, session_id, mastered_at) "
                        + "VALUES (?, ?, ?, ?, ?) AS new "
                        + "ON DUPLICATE KEY UPDATE session_id = new.session_id, "
                        + "mastered_at = new.mastered_at",
                rows);
    }

    /**
     * Retire les entrees inscrites par une partie invalidee apres coup. Une entree
     * dont la date a ete remise a jour par une partie plus recente porte l'id de
     * celle-ci et reste en place.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void forgetSession(Long sessionId) {
        jdbc.update("DELETE FROM solo_mastered_questions WHERE session_id = ?", sessionId);
    }

    @Transactional(readOnly = true)
    public List<SubjectCount> countBySubject(Long userId) {
        return jdbc.query(
                "SELECT m.category_id, c.name, COUNT(*) AS n FROM solo_mastered_questions m "
                        + "JOIN categories c ON c.id = m.category_id "
                        + "WHERE m.user_id = ? GROUP BY m.category_id, c.name ORDER BY n DESC, c.name",
                (rs, i) -> new SubjectCount(rs.getLong(1), rs.getString(2), rs.getLong(3)),
                userId);
    }

    private void remove(Long userId, Long categoryId, List<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(questionIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(categoryId);
        args.addAll(questionIds);
        jdbc.update("DELETE FROM solo_mastered_questions WHERE user_id = ? AND category_id = ? "
                + "AND question_id IN (" + placeholders + ")", args.toArray());
    }
}
