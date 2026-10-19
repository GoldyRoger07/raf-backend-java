package com.raikiservice.raf.game.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.domain.GameSession;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.game.domain.QuizRanking;
import com.raikiservice.raf.game.repository.GameSessionRepository;
import com.raikiservice.raf.game.repository.QuizRankingRepository;
import com.raikiservice.raf.game.web.dto.LeaderboardResponse;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizReward;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.RewardService;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classement final et versement des gains.
 *
 * <p>Fait passer le quiz de COMPUTING a FINISHED. Classement et paiements sont
 * ecrits dans une seule transaction, sous le verrou du quiz : soit tout est
 * fige et paye, soit rien ne l'est et le calcul sera rejoue au tour suivant.
 * Les cles d'idempotence des credits protegent en plus d'un double paiement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    private final QuizRepository quizRepository;
    private final GameSessionRepository gameSessionRepository;
    private final QuizRankingRepository quizRankingRepository;
    private final RewardService rewardService;
    private final WalletService walletService;
    private final AuditService auditService;

    /**
     * Fige le classement et verse les gains, si le quiz est pret.
     *
     * <p>Le quiz n'est pas pret tant qu'une partie est encore IN_PROGRESS, meme
     * echue : une soumission peut etre en vol, et cette transaction ne la verrait
     * pas (sous REPEATABLE READ, l'instantane est pris a la premiere lecture). Les
     * parties echues sont closes par {@link GameService#expireOverdue} dans une
     * transaction separee, et le calcul reprend au tour suivant.
     *
     * @return true si le quiz est passe en FINISHED
     */
    @Transactional
    public boolean finalizeQuiz(Long actorId, Long quizId) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (quiz.getStatus() != QuizStatus.COMPUTING) {
            return false;
        }

        if (gameSessionRepository.countByQuizIdAndStatus(quizId, GameSessionStatus.IN_PROGRESS) > 0) {
            log.debug("Quiz {} : parties encore en cours, classement differe", quizId);
            return false;
        }

        List<QuizReward> rewards = rewardService.forQuiz(quizId);
        Instant now = Instant.now();

        // Les sessions arrivent deja triees : score decroissant, puis debut de
        // partie croissant. La premiere rencontree pour un joueur est donc sa
        // meilleure, et c'est le debut de CETTE partie qui sert au departage.
        Set<Long> ranked = new HashSet<>();
        List<QuizRanking> rankings = new ArrayList<>();
        int rank = 1;

        for (GameSession session : gameSessionRepository.findRankable(quizId)) {
            User user = session.getUser();
            if (!ranked.add(user.getId())) {
                continue;
            }

            QuizRanking ranking = new QuizRanking();
            ranking.setQuiz(quiz);
            ranking.setUser(user);
            ranking.setBestSessionId(session.getId());
            ranking.setScore(session.getScore());
            ranking.setFirstStartedAt(session.getStartedAt());
            ranking.setRank(rank);
            ranking.setRewardCredits(rewardFor(rewards, rank));
            rankings.add(quizRankingRepository.save(ranking));
            rank++;
        }

        long totalPaid = 0;
        int winners = 0;
        for (QuizRanking ranking : rankings) {
            if (ranking.getRewardCredits() <= 0) {
                continue;
            }

            Long userId = ranking.getUser().getId();
            WalletTransaction credit = walletService.credit(
                    userId,
                    BalanceType.WINNINGS,
                    ranking.getRewardCredits(),
                    WalletTransactionType.PRIZE,
                    "QuizRanking",
                    String.valueOf(ranking.getId()),
                    "prize:quiz:" + quizId + ":user:" + userId,
                    ranking.getRank() + "e place - " + quiz.getTitle());

            ranking.setPaidAt(now);
            ranking.setTransactionId(credit.getId());
            totalPaid += ranking.getRewardCredits();
            winners++;
        }

        quiz.setStatus(QuizStatus.FINISHED);
        quiz.setFinishedAt(now);

        auditService.record(actorId, "QUIZ_FINISHED", "Quiz", String.valueOf(quizId),
                QuizStatus.COMPUTING.name(),
                rankings.size() + " joueur(s) classe(s), " + winners + " gagnant(s), "
                        + totalPaid + " credits verses");
        log.info("Quiz {} termine : {} classe(s), {} credits verses a {} gagnant(s)",
                quizId, rankings.size(), totalPaid, winners);

        return true;
    }

    /** Declenchement manuel, pour un administrateur qui ne veut pas attendre le tour suivant. */
    @Transactional
    public void finalizeByAdmin(Long actorId, Long quizId) {
        if (finalizeQuiz(actorId, quizId)) {
            return;
        }

        QuizStatus status = quizRepository.findById(quizId)
                .map(Quiz::getStatus)
                .orElseThrow(() -> ApiException.notFound("Quiz"));
        if (status != QuizStatus.COMPUTING) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Seul un quiz en cours de calcul peut etre cloture.");
        }
        throw new ApiException(ErrorCode.GAME_STILL_RUNNING);
    }

    /** Les rangs sans tranche (au-dela de la derniere) ne touchent rien. */
    private long rewardFor(List<QuizReward> rewards, int rank) {
        for (QuizReward reward : rewards) {
            if (rank >= reward.getRankFrom() && rank <= reward.getRankTo()) {
                return reward.getAmountCredits();
            }
        }
        return 0;
    }

    @Transactional(readOnly = true)
    public LeaderboardResponse leaderboard(Long quizId, Long viewerId, Pageable pageable) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (quiz.getStatus() != QuizStatus.FINISHED) {
            throw new ApiException(ErrorCode.GAME_RESULTS_NOT_READY);
        }

        long rate = walletService.htgRate();
        Page<QuizRanking> page = quizRankingRepository.findByQuizIdOrderByRankAsc(quizId, pageable);

        List<LeaderboardResponse.Entry> entries = page.getContent().stream()
                .map(ranking -> toEntry(ranking, viewerId, rate))
                .toList();

        Optional<QuizRanking> mine = viewerId == null ? Optional.empty()
                : quizRankingRepository.findByQuizIdAndUserId(quizId, viewerId);

        return new LeaderboardResponse(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getFinishedAt(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize(),
                entries,
                mine.map(ranking -> toEntry(ranking, viewerId, rate)).orElse(null));
    }

    private LeaderboardResponse.Entry toEntry(QuizRanking ranking, Long viewerId, long rate) {
        User user = ranking.getUser();
        return new LeaderboardResponse.Entry(
                ranking.getRank(),
                displayName(user),
                ranking.getScore(),
                ranking.getRewardCredits(),
                ranking.getRewardCredits() * rate,
                user.getId().equals(viewerId));
    }

    /** "Jean D." : le classement est visible de tous, le nom complet n'a pas a l'etre. */
    static String displayName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        return last.isEmpty() ? first : first + " " + Character.toUpperCase(last.charAt(0)) + ".";
    }
}
