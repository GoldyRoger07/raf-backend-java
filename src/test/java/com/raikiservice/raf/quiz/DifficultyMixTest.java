package com.raikiservice.raf.quiz;

import java.util.Map;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.quiz.domain.DifficultyMix;
import com.raikiservice.raf.quiz.web.dto.DifficultyMixDto;

import org.junit.jupiter.api.Test;

import static com.raikiservice.raf.question.domain.Difficulty.EASY;
import static com.raikiservice.raf.question.domain.Difficulty.HARD;
import static com.raikiservice.raf.question.domain.Difficulty.MEDIUM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Repartition par difficulte : arrondi et repli, sans base. */
class DifficultyMixTest {

    @Test
    void exactSharesNeedNoRounding() {
        assertThat(new DifficultyMix(40, 40, 20).split(10))
                .isEqualTo(Map.of(EASY, 4, MEDIUM, 4, HARD, 2));
    }

    @Test
    void leftoversGoToTheLargestRemainderThenToTheHardest() {
        // 3,3 / 3,3 / 3,4 : la question restante va au plus fort reste.
        assertThat(new DifficultyMix(33, 33, 34).split(10))
                .isEqualTo(Map.of(EASY, 3, MEDIUM, 3, HARD, 4));
        // 1,5 / 1,5 / 0 : egalite, le plus difficile des deux l'emporte.
        assertThat(new DifficultyMix(50, 50, 0).split(3))
                .isEqualTo(Map.of(EASY, 1, MEDIUM, 2, HARD, 0));
        // 2,31 / 2,31 / 2,38 sur 7 : la seule question restante va au difficile.
        assertThat(new DifficultyMix(33, 33, 34).split(7))
                .isEqualTo(Map.of(EASY, 2, MEDIUM, 2, HARD, 3));
    }

    @Test
    void theSplitAlwaysAddsUpAndSkipsEmptyLevels() {
        for (int n = 1; n <= 60; n++) {
            Map<Difficulty, Integer> split = new DifficultyMix(15, 0, 85).split(n);
            assertThat(split.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(n);
            assertThat(split.get(MEDIUM)).isZero();
        }
    }

    @Test
    void aShortLevelBorrowsFromItsNeighbourFirst() {
        Map<Difficulty, Integer> targets = Map.of(EASY, 2, MEDIUM, 2, HARD, 6);
        // 2 difficiles seulement : les 4 manquantes viennent des moyennes, puis des faciles.
        assertThat(DifficultyMix.allocate(targets, Map.of(EASY, 10, MEDIUM, 4, HARD, 2)))
                .isEqualTo(Map.of(EASY, 4, MEDIUM, 4, HARD, 2));
        // Moyennes absentes : le difficile complete avant le facile.
        assertThat(DifficultyMix.allocate(Map.of(EASY, 0, MEDIUM, 3, HARD, 0),
                Map.of(EASY, 10, MEDIUM, 0, HARD, 10)))
                .isEqualTo(Map.of(EASY, 0, MEDIUM, 0, HARD, 3));
    }

    @Test
    void theDtoAcceptsAllOrNothing() {
        assertThat(DifficultyMixDto.toMix(null, null, null)).isNull();
        assertThat(DifficultyMixDto.toMix(20, 50, 30)).isEqualTo(new DifficultyMix(20, 50, 30));
        assertThatThrownBy(() -> DifficultyMixDto.toMix(50, 50, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DifficultyMixDto.toMix(50, 40, 20)).isInstanceOf(ApiException.class);
    }
}
