package com.raikiservice.raf.notification.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.notification.domain.NotificationType;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.TopicMode;
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

    // --- Recharge par le leader --------------------------------------------------

    /** Meme type qu'une recharge confirmee : memes preferences pour le joueur. */
    public static NotificationMessage leaderTransferReceived(String leaderName, long credits) {
        return new NotificationMessage(NotificationType.TOPUP_CONFIRMED, "Recharge recue",
                leaderName + " vous a envoye " + credits + " credit(s), deja disponibles sur votre solde.",
                Map.of("credits", credits), "/portefeuille");
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

    public static NotificationMessage prizeWon(Quiz quiz, int rank, long rewardHtg) {
        return new NotificationMessage(NotificationType.PRIZE_WON, "Felicitations, vous avez gagne !",
                "Vous terminez " + ordinal(rank) + " de « " + quiz.getTitle() + " » et remportez "
                        + rewardHtg + " HTG, credites sur votre solde de gains.",
                Map.of("quizId", quiz.getId(), "rank", rank, "rewardHtg", rewardHtg),
                "/quiz/" + quiz.getId() + "/classement");
    }

    /** Sans-faute en solo : meme type que les gains du classement, memes preferences. */
    public static NotificationMessage soloPrizeWon(Quiz quiz, Long sessionId, long prizeHtg) {
        return new NotificationMessage(NotificationType.PRIZE_WON, "Sans-faute, vous avez gagne !",
                "Toutes vos reponses a « " + quiz.getTitle() + " » sont justes : vous remportez "
                        + prizeHtg + " HTG, credites sur votre solde de gains.",
                Map.of("quizId", quiz.getId(), "sessionId", sessionId, "rewardHtg", prizeHtg),
                "/portefeuille");
    }

    /** Ouverture d'un quiz solo, annoncee a tous les joueurs. */
    public static NotificationMessage soloQuizOpened(Quiz quiz) {
        Long prize = quiz.getSoloPrizeHtg();
        String body = prize != null && prize > 0
                ? "« " + quiz.getTitle() + " » est ouvert : faites un sans-faute et remportez "
                        + prize + " HTG."
                : "« " + quiz.getTitle() + " » est ouvert : lancez votre partie quand vous voulez.";
        return quiz(NotificationType.SOLO_QUIZ_OPENED, quiz, "Nouveau quiz solo", body);
    }

    /** Publication d'un quiz de groupe (tickets en vente), annoncee a tous les joueurs. */
    public static NotificationMessage groupQuizOpened(Quiz quiz) {
        String ticket = quiz.isFree() ? "le ticket est gratuit"
                : "le ticket coute " + quiz.getTicketPriceCredits() + " credit(s)";
        String body = quiz.getTopicMode() == TopicMode.VOTE
                ? "« " + quiz.getTitle() + " » est ouvert : " + ticket
                        + ". Prenez le votre et votez pour le sujet."
                : "« " + quiz.getTitle() + " » est ouvert : " + ticket + ". Prenez le votre !";
        return quiz(NotificationType.GROUP_QUIZ_OPENED, quiz, "Nouveau quiz de groupe", body);
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
        return topUpConfirmed(topUpId, credits, amountHtg, 0, 0);
    }

    /**
     * Recharge avec retenue : on dit au joueur ce qui a ete repris de sa retenue et ce
     * qui est mis de cote, pour qu'il ne croie pas avoir perdu des gourdes.
     */
    public static NotificationMessage topUpConfirmed(Long topUpId, long credits, long amountHtg,
            long retentionUsedHtg, long retentionKeptHtg) {
        StringBuilder body = new StringBuilder();
        if (credits > 0) {
            body.append("Recharge de ").append(amountHtg).append(" HTG");
            if (retentionUsedHtg > 0) {
                body.append(" + ").append(retentionUsedHtg).append(" HTG de votre retenue");
            }
            body.append(" : ").append(credits).append(" credit(s) ajoute(s) a votre solde.");
        } else {
            body.append("Recharge de ").append(amountHtg).append(" HTG recue, pas encore assez pour un credit.");
        }
        if (retentionKeptHtg > 0) {
            body.append(" ").append(retentionKeptHtg)
                    .append(" HTG sont gardes et seront ajoutes a votre prochaine recharge.");
        }
        return new NotificationMessage(NotificationType.TOPUP_CONFIRMED, "Recharge confirmee", body.toString(),
                Map.of("topUpId", topUpId), "/portefeuille");
    }

    /** Recharge avec les gains : le reste eventuel est simplement reste dans les gains. */
    public static NotificationMessage winningsConverted(Long topUpId, long credits, long amountHtg) {
        return new NotificationMessage(NotificationType.TOPUP_CONFIRMED, "Recharge avec vos gains",
                amountHtg + " HTG de vos gains convertis : " + credits + " credit(s) ajoute(s) a votre solde.",
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

    public static NotificationMessage withdrawalPartiallyPaid(WithdrawalRequest request, long amountHtg) {
        return withdrawal(NotificationType.WITHDRAWAL_PARTIALLY_PAID, request, "Retrait verse en partie",
                amountHtg + " HTG ont ete verses sur votre compte " + request.getProvider() + ". "
                        + "Total verse : " + request.getPaidHtg() + " HTG sur " + request.getNetHtg()
                        + " HTG. Le reste vous sera envoye des que possible.");
    }

    public static NotificationMessage withdrawalClosedWithRefund(WithdrawalRequest request, String reason) {
        return withdrawal(NotificationType.WITHDRAWAL_CLOSED, request, "Retrait clos, reste rendu",
                request.getPaidHtg() + " HTG vous ont ete verses sur " + request.getNetHtg() + " HTG. Les "
                        + request.getRefundedHtg() + " HTG restants ont ete remis dans vos gains. Motif : "
                        + reason);
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

    public static NotificationMessage withdrawalRequested(WithdrawalRequest request) {
        User player = request.getUser();
        String destination = request.getProvider() == PaymentProvider.MANUAL
                ? "en especes au guichet"
                : "sur " + request.getProvider() + " (" + request.getDestinationNumber() + ")";
        return new NotificationMessage(NotificationType.ADMIN_WITHDRAWAL_REQUESTED,
                "Nouvelle demande de retrait",
                player.getFirstName() + " " + player.getLastName() + " (" + player.getPhone()
                        + ") demande un retrait de " + request.getAmountHtg() + " HTG, soit "
                        + request.getNetHtg() + " HTG nets apres " + request.getFeeHtg()
                        + " HTG de frais, " + destination + ". Ses gains sont geles en "
                        + "attendant votre decision.",
                Map.of("withdrawalId", request.getId()),
                "/admin/retraits?statut=PENDING");
    }

    public static NotificationMessage withdrawalPayoutFailed(WithdrawalRequest request) {
        User player = request.getUser();
        return new NotificationMessage(NotificationType.ADMIN_WITHDRAWAL_PAYOUT_FAILED,
                "Envoi automatique d'un retrait echoue",
                "Le retrait de " + request.getNetHtg() + " HTG de " + player.getFirstName() + " "
                        + player.getLastName() + " sur " + request.getProvider() + " ("
                        + request.getDestinationNumber() + ") n'a pas pu etre envoye : "
                        + request.getPayoutError() + ". Les gains restent geles : relancez l'envoi, "
                        + "versez a la main ou rejetez la demande.",
                Map.of("withdrawalId", request.getId()),
                "/admin/retraits?statut=APPROVED");
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
