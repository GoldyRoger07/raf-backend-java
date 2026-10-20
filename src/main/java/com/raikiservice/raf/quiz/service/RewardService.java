package com.raikiservice.raf.quiz.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizReward;
import com.raikiservice.raf.quiz.repository.QuizRewardRepository;
import com.raikiservice.raf.quiz.web.dto.RewardTierDto;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuration des recompenses.
 *
 * <p>Les montants sont fixes : la cagnotte ne depend pas des ventes. Le seuil
 * minimum de joueurs devient donc la protection financiere du quiz, et cette
 * classe calcule le point d'equilibre pour que l'administrateur ne puisse pas
 * publier un quiz structurellement deficitaire sans en etre averti.
 */
@Service
@RequiredArgsConstructor
public class RewardService {

    private final QuizRewardRepository quizRewardRepository;
    private final WalletService walletService;
    private final AuditService auditService;

    /**
     * Bilan financier previsionnel d'un quiz, en HTG : les recompenses sont en
     * gourdes, les tickets en credits convertis au taux du moment.
     */
    public record Economics(
            long totalRewardsHtg,
            int ticketPriceCredits,
            long ticketPriceHtg,
            int minPlayers,
            long breakEvenPlayers,
            long minRevenueHtg,
            long marginAtMinPlayersHtg,
            boolean profitableAtMinPlayers,
            int rewardedPlaces) {
    }

    @Transactional
    public List<QuizReward> configure(Long actorId, Quiz quiz, List<RewardTierDto> tiers) {
        if (quiz.isSolo()) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Un quiz solo n'a pas de classement : son prix se regle sur le quiz.");
        }
        validate(tiers);

        quizRewardRepository.deleteByQuizId(quiz.getId());
        quizRewardRepository.flush();

        List<QuizReward> saved = new ArrayList<>();
        for (RewardTierDto tier : tiers) {
            QuizReward reward = new QuizReward();
            reward.setQuiz(quiz);
            reward.setRankFrom(tier.rankFrom());
            reward.setRankTo(tier.rankTo());
            reward.setAmountHtg(tier.amountHtg());
            reward.setLabel(tier.label());
            saved.add(quizRewardRepository.save(reward));
        }

        Economics economics = economics(quiz, saved);
        auditService.record(actorId, "QUIZ_REWARDS_CONFIGURED", "Quiz",
                String.valueOf(quiz.getId()), null,
                economics.totalRewardsHtg() + " HTG sur "
                        + economics.rewardedPlaces() + " place(s)");

        return saved;
    }

    @Transactional(readOnly = true)
    public List<QuizReward> forQuiz(Long quizId) {
        return quizRewardRepository.findByQuizIdOrderByRankFromAsc(quizId);
    }

    @Transactional(readOnly = true)
    public Economics economics(Quiz quiz) {
        return economics(quiz, forQuiz(quiz.getId()));
    }

    public Economics economics(Quiz quiz, List<QuizReward> rewards) {
        long totalRewards = rewards.stream().mapToLong(QuizReward::totalCost).sum();
        int places = rewards.stream().mapToInt(QuizReward::places).sum();
        long price = quiz.getTicketPriceCredits() * walletService.htgRate();

        // Un ticket = une partie, mais le seuil compte des JOUEURS distincts :
        // le point d'equilibre est donc calcule sur un ticket par joueur, soit
        // l'hypothese la plus defavorable.
        long breakEven = price <= 0 ? Long.MAX_VALUE
                : (totalRewards + price - 1) / price;
        long minRevenue = price * quiz.getMinPlayers();

        return new Economics(
                totalRewards,
                quiz.getTicketPriceCredits(),
                price,
                quiz.getMinPlayers(),
                breakEven,
                minRevenue,
                minRevenue - totalRewards,
                minRevenue >= totalRewards,
                places);
    }

    /**
     * Les tranches doivent etre disjointes et contigues a partir du rang 1.
     *
     * <p>Un trou entre deux tranches passerait inapercu a la configuration et se
     * traduirait par un joueur bien classe qui ne touche rien le jour des
     * resultats, ce qui est indefendable.
     */
    private void validate(List<RewardTierDto> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new ApiException(ErrorCode.QUIZ_NO_REWARDS);
        }

        List<RewardTierDto> sorted = tiers.stream()
                .sorted(Comparator.comparingInt(RewardTierDto::rankFrom))
                .toList();

        if (sorted.get(0).rankFrom() != 1) {
            throw new ApiException(ErrorCode.QUIZ_REWARDS_OVERLAP,
                    "La premiere tranche doit commencer au rang 1.");
        }

        int expectedNext = 1;
        for (RewardTierDto tier : sorted) {
            if (tier.rankTo() < tier.rankFrom()) {
                throw new ApiException(ErrorCode.QUIZ_REWARDS_OVERLAP,
                        "Tranche invalide : le rang de fin precede le rang de debut.");
            }
            if (tier.rankFrom() != expectedNext) {
                throw new ApiException(ErrorCode.QUIZ_REWARDS_OVERLAP,
                        tier.rankFrom() > expectedNext
                                ? "Les rangs " + expectedNext + " a " + (tier.rankFrom() - 1)
                                        + " ne sont couverts par aucune tranche."
                                : "La tranche commencant au rang " + tier.rankFrom()
                                        + " chevauche la precedente.");
            }
            expectedNext = tier.rankTo() + 1;
        }
    }
}
