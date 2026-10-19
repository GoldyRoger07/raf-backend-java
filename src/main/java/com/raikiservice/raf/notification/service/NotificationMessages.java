package com.raikiservice.raf.notification.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import com.raikiservice.raf.notification.domain.NotificationType;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;

/**
 * Textes des notifications, regroupes ici pour rester coherents et faciles a
 * relire. Les heures sont affichees a l'heure d'Haiti, les montants en gourdes.
 */
public final class NotificationMessages {

    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH'h'mm", Locale.FRENCH).withZone(HAITI);
    private static final DateTimeFormatter DAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM 'a' HH'h'mm", Locale.FRENCH).withZone(HAITI);

    private NotificationMessages() {
    }

    // --- Quiz -------------------------------------------------------------------

    public static NotificationMessage quizScheduled(Quiz quiz) {
        return quiz(NotificationType.QUIZ_SCHEDULED, quiz, "Le quiz va commencer",
                "Assez de joueurs sont inscrits : « " + quiz.getTitle() + " » demarre le "
                        + format(DAY_TIME, quiz.getScheduledStartAt()) + ".");
    }

    public static NotificationMessage quizStarted(Quiz quiz) {
        return quiz(NotificationType.QUIZ_STARTED, quiz, "C'est parti !",
                "« " + quiz.getTitle() + " » a commence : lancez votre partie quand vous voulez.");
    }

    public static NotificationMessage quizClosing(Quiz quiz) {
        return quiz(NotificationType.QUIZ_CLOSING, quiz, "Derniere ligne droite",
                "Un joueur a depasse " + quiz.getMinScoreThreshold() + " points : « "
                        + quiz.getTitle() + " » se termine a " + format(TIME, quiz.getEndsAt())
                        + ". Jouez vos tickets avant.");
    }

    public static NotificationMessage quizCancelled(Quiz quiz, String reason) {
        return quiz(NotificationType.QUIZ_CANCELLED, quiz, "Quiz annule, tickets rembourses",
                "« " + quiz.getTitle() + " » est annule et vos tickets vous ont ete rembourses "
                        + "en credits. Motif : " + reason);
    }

    public static NotificationMessage prizeWon(Quiz quiz, int rank, long rewardCredits, long htgRate) {
        return new NotificationMessage(NotificationType.PRIZE_WON, "Felicitations, vous avez gagne !",
                "Vous terminez " + ordinal(rank) + " de « " + quiz.getTitle() + " » et remportez "
                        + rewardCredits * htgRate + " HTG, credites sur votre solde de gains.",
                Map.of("quizId", quiz.getId(), "rank", rank, "rewardCredits", rewardCredits),
                "/quiz/" + quiz.getId() + "/classement");
    }

    public static NotificationMessage quizResults(Quiz quiz, int rank, long ranked) {
        return new NotificationMessage(NotificationType.QUIZ_RESULTS, "Les resultats sont tombes",
                "Vous terminez " + ordinal(rank) + " sur " + ranked + " a « " + quiz.getTitle()
                        + " ». Le corrige detaille est disponible.",
                Map.of("quizId", quiz.getId(), "rank", rank),
                "/quiz/" + quiz.getId() + "/classement");
    }

    // --- Portefeuille -----------------------------------------------------------

    public static NotificationMessage topUpConfirmed(Long topUpId, long credits, long amountHtg) {
        return new NotificationMessage(NotificationType.TOPUP_CONFIRMED, "Recharge confirmee",
                credits + " credits (" + amountHtg + " HTG) ont ete ajoutes a votre solde.",
                Map.of("topUpId", topUpId), "/portefeuille");
    }

    public static NotificationMessage withdrawalApproved(WithdrawalRequest request) {
        String body = request.getProvider() == PaymentProvider.MANUAL
                ? "Votre retrait de " + request.getNetHtg() + " HTG est pret : presentez-vous au "
                        + "guichet RAF avec votre telephone, un code vous sera envoye sur place."
                : "Votre retrait de " + request.getNetHtg() + " HTG est approuve et sera verse sur "
                        + "votre compte " + request.getProvider() + ".";
        return withdrawal(NotificationType.WITHDRAWAL_APPROVED, request, "Retrait approuve", body);
    }

    public static NotificationMessage withdrawalPaid(WithdrawalRequest request) {
        String body = request.getProvider() == PaymentProvider.MANUAL
                ? request.getNetHtg() + " HTG vous ont ete remis en especes au guichet."
                : request.getNetHtg() + " HTG ont ete verses sur votre compte "
                        + request.getProvider() + ".";
        return withdrawal(NotificationType.WITHDRAWAL_PAID, request, "Retrait verse", body);
    }

    public static NotificationMessage withdrawalRejected(WithdrawalRequest request, String reason) {
        return withdrawal(NotificationType.WITHDRAWAL_REJECTED, request, "Retrait refuse",
                "Votre retrait de " + request.getAmountHtg() + " HTG a ete refuse et vos gains "
                        + "vous ont ete rendus. Motif : " + reason);
    }

    // --- Administration ---------------------------------------------------------

    public static NotificationMessage reconciliationAlert(Long runId, String status, int findings,
            String error) {
        String body = error != null
                ? "Le rapprochement comptable n'a pas pu aboutir : " + error
                : "Le rapprochement comptable a releve " + findings + " ecart(s). "
                        + "Chaque ecart est une erreur de code ou une modification manuelle de la "
                        + "base : a examiner avant tout autre versement.";
        return new NotificationMessage(NotificationType.ADMIN_RECONCILIATION_ALERT,
                "Alerte comptable : " + status, body, Map.of("runId", runId),
                "/admin/rapprochement/" + runId);
    }

    // --- Outils -------------------------------------------------------------------

    private static NotificationMessage quiz(NotificationType type, Quiz quiz, String title,
            String body) {
        return new NotificationMessage(type, title, body, Map.of("quizId", quiz.getId()),
                "/quiz/" + quiz.getId());
    }

    private static NotificationMessage withdrawal(NotificationType type, WithdrawalRequest request,
            String title, String body) {
        return new NotificationMessage(type, title, body,
                Map.of("withdrawalId", request.getId()), "/portefeuille/retraits");
    }

    private static String format(DateTimeFormatter formatter, Instant instant) {
        return instant == null ? "bientot" : formatter.format(instant);
    }

    private static String ordinal(int rank) {
        return rank == 1 ? "1re" : rank + "e";
    }
}
