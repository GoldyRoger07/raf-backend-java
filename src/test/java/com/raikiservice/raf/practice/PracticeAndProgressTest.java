package com.raikiservice.raf.practice;

import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.practice.service.PracticeService;
import com.raikiservice.raf.practice.service.PracticeService.AnswerResult;
import com.raikiservice.raf.practice.service.PracticeService.SessionView;
import com.raikiservice.raf.practice.service.ProgressService;
import com.raikiservice.raf.practice.service.ProgressService.Mission;
import com.raikiservice.raf.practice.service.ProgressService.Progress;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.service.CategoryService;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Entrainement gratuit, progression (XP, niveaux, missions, badges) et montee en difficulte. */
class PracticeAndProgressTest extends IntegrationTest {

    @Autowired private PracticeService practice;
    @Autowired private ProgressService progress;
    @Autowired private CategoryService categoryService;
    @Autowired private SettingsService settingsService;
    @Autowired private GameService gameService;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuizRepository quizRepository;

    @AfterEach
    void resetLimit() {
        settingsService.update(null, SettingKeys.APP_PRACTICE_DAILY_LIMIT,
                String.valueOf(PracticeService.DEFAULT_DAILY_LIMIT));
    }

    private Long practiceSubject() {
        Long categoryId = data.categoryWithQuestions(4, 4, 4);
        categoryService.configureModes(null, categoryId, null, null, true);
        return categoryId;
    }

    /** Repond a toute la partie : juste partout sauf a la position {@code wrongAt} (0 = aucune erreur). */
    private SessionView playPractice(User player, Long categoryId, int wrongAt) {
        SessionView session = practice.start(player.getId(), categoryId);
        for (PracticeService.QuestionView question : session.questions()) {
            Long choice = question.position() == wrongAt ? data.wrongChoice(question.id()) : data.correctChoice(question.id());
            session = practice.answer(player.getId(), session.sessionId(), question.id(), choice).session();
        }
        return session;
    }

    @Test
    void aPracticeSubjectIsNeverPaid() {
        Long categoryId = practiceSubject();
        var category = categoryService.require(categoryId);
        assertThat(category.isPracticeEnabled()).isTrue();
        assertThat(category.isAvailableFor(GameMode.SOLO)).isFalse();
        assertThat(category.isAvailableFor(GameMode.GROUP)).isFalse();

        // Le remettre en solo est refuse tant que l'entrainement est actif.
        assertApiError(ErrorCode.CATEGORY_MODE_DISABLED,
                () -> categoryService.configureModes(null, categoryId, null, true, null));
    }

    @Test
    void aSubjectUsedByAnOpenQuizCannotBecomeAPracticeSubject() {
        Long categoryId = data.categoryWithQuestions(60);
        data.soloQuiz(categoryId, 5);
        assertApiError(ErrorCode.CATEGORY_MODE_DISABLED,
                () -> categoryService.configureModes(null, categoryId, null, null, true));
    }

    @Test
    void practiceShowsTheCorrectionRightAwayAndGoesFromEasyToHard() {
        User player = data.player();
        Long categoryId = practiceSubject();

        SessionView session = practice.start(player.getId(), categoryId);
        assertThat(session.total()).isEqualTo(PracticeService.QUESTIONS);
        // Aucune correction avant de repondre.
        assertThat(session.questions()).allSatisfy(q -> assertThat(q.correctChoiceId()).isNull());
        List<Difficulty> order = session.questions().stream().map(PracticeService.QuestionView::difficulty).toList();
        assertThat(order).isSortedAccordingTo(Enum::compareTo);

        var first = session.questions().get(0);
        AnswerResult result = practice.answer(player.getId(), session.sessionId(), first.id(), data.wrongChoice(first.id()));
        assertThat(result.correct()).isFalse();
        assertThat(result.correctChoiceId()).isEqualTo(data.correctChoice(first.id()));

        // Une seule reponse par question.
        assertApiError(ErrorCode.GAME_INVALID_ANSWER, () -> practice.answer(player.getId(), session.sessionId(),
                first.id(), data.correctChoice(first.id())));
    }

    @Test
    void practiceIsFreeAndLimitedPerDay() {
        settingsService.update(null, SettingKeys.APP_PRACTICE_DAILY_LIMIT, "2");
        User player = data.player();
        Long categoryId = practiceSubject();

        playPractice(player, categoryId, 0);
        // Une partie non finie se reprend sans compter de nouveau.
        SessionView started = practice.start(player.getId(), categoryId);
        assertThat(practice.start(player.getId(), categoryId).sessionId()).isEqualTo(started.sessionId());
        assertThat(practice.overview(player.getId()).remainingToday()).isZero();

        for (var q : started.questions()) {
            practice.answer(player.getId(), started.sessionId(), q.id(), null);
        }
        assertApiError(ErrorCode.PRACTICE_DAILY_LIMIT, () -> practice.start(player.getId(), categoryId));
    }

    @Test
    void progressCountsXpMissionsAndBadges() {
        User player = data.player();
        Progress empty = progress.of(player.getId());
        assertThat(empty.totalXp()).isZero();
        assertThat(empty.level()).isEqualTo(1);

        // 5/5 en entrainement : 10 + 5 x 5 = 35 XP, mission "joue une partie" (+15).
        SessionView done = playPractice(player, practiceSubject(), 0);
        assertThat(done.finished()).isTrue();
        Progress after = progress.of(player.getId());
        assertThat(after.totalXp()).isEqualTo(ProgressService.XP_PRACTICE + 5L * ProgressService.XP_PRACTICE_CORRECT
                + Mission.PLAY_ONE.xp);
        assertThat(after.currentStreakDays()).isEqualTo(1);
        assertThat(after.missions()).filteredOn(m -> m.id() == Mission.PLAY_ONE).allMatch(m -> m.done());
        assertThat(after.badges()).filteredOn(b -> b.id() == ProgressService.Badge.FIRST_GAME)
                .allMatch(ProgressService.BadgeView::achieved);

        // Deuxieme sujet : 10 bonnes reponses et 2 sujets, toutes les missions et le bonus.
        playPractice(player, practiceSubject(), 0);
        Progress all = progress.of(player.getId());
        assertThat(all.allMissionsDone()).isTrue();
        long expected = 2L * (ProgressService.XP_PRACTICE + 5L * ProgressService.XP_PRACTICE_CORRECT)
                + Mission.PLAY_ONE.xp + Mission.CORRECT_TEN.xp + Mission.TWO_SUBJECTS.xp + ProgressService.XP_ALL_MISSIONS;
        assertThat(all.totalXp()).isEqualTo(expected);
        assertThat(all.level()).isEqualTo(2);
        assertThat(all.levelXp()).isEqualTo(expected - ProgressService.xpForNextLevel(1));
    }

    @Test
    void soloGamesGoFromEasyToHard() {
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, TestData.SOLO_ENTRY);
        Long quizId = data.soloQuiz(data.categoryWithQuestions(20, 20, 20), 6);
        GameStartResponse game = gameService.start(player.getId(), quizId, "127.0.0.1", "junit", null);

        java.util.List<Difficulty> served = new java.util.ArrayList<>();
        GameStartResponse.CurrentQuestion current = game.current();
        GameResultResponse result = null;
        while (current != null) {
            Long id = current.question().id();
            served.add(questionRepository.findById(id).orElseThrow().getDifficulty());
            var turn = gameService.answer(player.getId(), game.sessionId(), new AnswerQuestionDto(id, data.wrongChoice(id)));
            current = turn.next();
            result = turn.result();
        }
        assertThat(result).isNotNull();
        assertThat(served).hasSize(6).isSortedAccordingTo(Enum::compareTo);

        // La partie solo rapporte de l'XP, meme perdue : 20 XP, aucune bonne reponse.
        assertThat(progress.of(player.getId()).totalXp()).isEqualTo(ProgressService.XP_GAME + Mission.PLAY_ONE.xp);
        Quiz quiz = quizRepository.findById(quizId).orElseThrow();
        assertThat(quiz.isHidden()).isFalse();
    }
}
