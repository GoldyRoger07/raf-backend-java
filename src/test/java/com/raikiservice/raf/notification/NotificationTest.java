package com.raikiservice.raf.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.service.RankingService;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.SubmitGameDto;
import com.raikiservice.raf.notification.domain.NotificationChannel;
import com.raikiservice.raf.notification.domain.NotificationType;
import com.raikiservice.raf.notification.repository.PushSubscriptionRepository;
import com.raikiservice.raf.notification.service.NotificationInboxService;
import com.raikiservice.raf.notification.service.NotificationMessage;
import com.raikiservice.raf.notification.service.NotificationPreferenceService;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.notification.web.dto.NotificationResponse;
import com.raikiservice.raf.notification.web.dto.PreferenceUpdateDto;
import com.raikiservice.raf.notification.web.dto.PushSubscriptionDto;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.service.WithdrawalService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notifications : ce qui part, sur quels canaux, et seulement apres le commit.
 */
class NotificationTest extends IntegrationTest {

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationInboxService inboxService;
    @Autowired private NotificationPreferenceService preferenceService;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
    @Autowired private GameService gameService;
    @Autowired private RankingService rankingService;
    @Autowired private QuizService quizService;
    @Autowired private TicketService ticketService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private WithdrawalService withdrawalService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private static Long categoryId;

    @BeforeEach
    void category() {
        if (categoryId == null) {
            categoryId = data.categoryWithQuestions(30);
        }
    }

    @Test
    void finishingAQuizTellsWinnersByEmailAndEveryoneByPush() throws Exception {
        User first = subscribed(data.player());
        User second = subscribed(data.player());
        User third = subscribed(data.player());
        Long quizId = data.runningQuiz(categoryId, 3, 1000, 1, first, second, third);

        eventually(() -> assertThat(types(third)).contains(NotificationType.QUIZ_STARTED));

        play(first, quizId, 3);
        play(second, quizId, 2);
        play(third, quizId, 1);
        close(quizId);
        rankingService.finalizeQuiz(null, quizId);

        eventually(() -> {
            assertThat(types(first)).contains(NotificationType.PRIZE_WON);
            assertThat(types(second)).contains(NotificationType.PRIZE_WON);
            assertThat(types(third)).contains(NotificationType.QUIZ_RESULTS)
                    .doesNotContain(NotificationType.PRIZE_WON);
        });
        // Recompense fixee en gourdes : annoncee telle quelle, sans conversion.
        assertThat(inbox(first).get(0).body()).contains("1re").contains("100 HTG");

        // Gain : email et push. Simple resultat : push seulement.
        assertThat(mails.to(first.getEmail())).anySatisfy(mail ->
                assertThat(mail.subject()).startsWith("Felicitations"));
        assertThat(mails.to(third.getEmail())).isEmpty();
        assertThat(pushes.to(third.getId())).anySatisfy(push ->
                assertThat(push.payload()).contains("QUIZ_RESULTS").contains("/quiz/" + quizId));
    }

    @Test
    void aPlayerCanTurnOffEmailsForAType() throws Exception {
        User player = data.player();
        preferenceService.update(player.getId(), List.of(
                new PreferenceUpdateDto(NotificationType.WITHDRAWAL_REJECTED, NotificationChannel.EMAIL, false)));

        rejectAWithdrawalOf(player, "piece d'identite illisible");

        eventually(() -> assertThat(types(player)).contains(NotificationType.WITHDRAWAL_REJECTED));
        assertThat(mails.to(player.getEmail())).isEmpty();
        assertThat(preferenceService.enabledChannels(player.getId(), NotificationType.WITHDRAWAL_REJECTED))
                .doesNotContain(NotificationChannel.EMAIL);
    }

    @Test
    void aRejectedWithdrawalIsExplainedByEmail() throws Exception {
        User player = data.player();

        rejectAWithdrawalOf(player, "piece d'identite illisible");

        eventually(() -> assertThat(mails.to(player.getEmail())).anySatisfy(mail ->
                assertThat(mail.html()).contains("piece d'identite illisible")));
    }

    @Test
    void nothingIsSentWhenTheTransactionRollsBack() throws Exception {
        User player = data.player();
        NotificationMessage message = new NotificationMessage(NotificationType.TOPUP_CONFIRMED,
                "Ne doit pas partir", "Transaction annulee", Map.of(), null);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notificationService.notifyUser(player.getId(), message);
            status.setRollbackOnly();
        });
        Thread.sleep(500);

        assertThat(inbox(player)).isEmpty();
        assertThat(pushes.to(player.getId())).isEmpty();
    }

    @Test
    void aCancelledQuizTellsEveryBuyerAboutTheRefund() throws Exception {
        User player = data.player();
        Long quizId = data.publishedQuiz(categoryId, 3, 1000, 1000, null, null);
        data.credit(player.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
        ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString());

        quizService.cancel(null, quizId, "probleme technique");

        eventually(() -> assertThat(mails.to(player.getEmail())).anySatisfy(mail ->
                assertThat(mail.html()).contains("probleme technique").contains("rembourses")));
    }

    @Test
    void theInboxCountsAndMarksOnlyItsOwnNotifications() throws Exception {
        User player = data.player();
        User other = data.player();
        for (int i = 0; i < 3; i++) {
            notifyNow(player.getId(), "Message " + i);
        }
        notifyNow(other.getId(), "Pour un autre");
        eventually(() -> assertThat(inboxService.unreadCount(player.getId())).isEqualTo(3));

        Long first = inbox(player).get(0).id();
        Long othersId = inbox(other).get(0).id();
        inboxService.markRead(player.getId(), first);
        assertThat(inboxService.unreadCount(player.getId())).isEqualTo(2);
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () -> inboxService.markRead(player.getId(), othersId));

        assertThat(inboxService.markAllRead(player.getId())).isEqualTo(2);
        assertThat(inboxService.unreadCount(player.getId())).isZero();
        assertThat(inboxService.unreadCount(other.getId())).isEqualTo(1);
    }

    @Test
    void aSubscriptionRevokedByTheBrowserIsForgotten() throws Exception {
        User player = subscribed(data.player());
        String endpoint = pushSubscriptionRepository.findByUserId(player.getId()).get(0).getEndpoint();
        pushes.revoke(endpoint);

        notifyNow(player.getId(), "Personne pour le recevoir");

        eventually(() -> assertThat(pushSubscriptionRepository.findByUserId(player.getId())).isEmpty());
    }

    @Test
    void adminsAreAlertedOfAccountingDiscrepancies() throws Exception {
        User admin = data.admin();
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, 10);

        jdbc.update("UPDATE wallets SET credit_balance = credit_balance + 3 WHERE user_id = ?", player.getId());
        try {
            reconciliationService.run(ReconciliationTrigger.MANUAL, null);
        } finally {
            jdbc.update("UPDATE wallets SET credit_balance = credit_balance - 3 WHERE user_id = ?", player.getId());
        }

        eventually(() -> assertThat(mails.to(admin.getEmail())).anySatisfy(mail ->
                assertThat(mail.subject()).startsWith("Alerte comptable")));
        assertThat(types(player)).doesNotContain(NotificationType.ADMIN_RECONCILIATION_ALERT);
    }

    // --- Outils -------------------------------------------------------------------

    private User subscribed(User player) {
        inboxService.subscribe(player.getId(), new PushSubscriptionDto(
                "https://push.example.test/" + UUID.randomUUID(),
                new PushSubscriptionDto.Keys("cle-p256dh", "cle-auth")), "junit");
        return player;
    }

    private void notifyNow(Long userId, String title) {
        notificationService.notifyUser(userId, new NotificationMessage(
                NotificationType.TOPUP_CONFIRMED, title, "Corps", Map.of(), "/portefeuille"));
    }

    private void rejectAWithdrawalOf(User player, String reason) {
        User agent = data.admin();
        data.credit(player.getId(), BalanceType.WINNINGS, 5000);
        withdrawalService.sendWithdrawalOtp(player.getId());
        WithdrawalRequest request = withdrawalService.request(player.getId(), 1500,
                PaymentProvider.MONCASH, player.getPhone(), sms.lastCode(player.getPhone()));
        withdrawalService.reject(agent.getId(), request.getId(), reason);
    }

    private List<NotificationResponse> inbox(User user) {
        return inboxService.list(user.getId(), false, PageRequest.of(0, 50)).getContent();
    }

    private List<NotificationType> types(User user) {
        return inbox(user).stream().map(NotificationResponse::type).toList();
    }

    private void play(User player, Long quizId, int correct) throws Exception {
        GameStartResponse game = gameService.start(player.getId(), quizId, "127.0.0.1", "junit");
        Thread.sleep(400);
        List<SubmitGameDto.AnswerDto> answers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long questionId = game.questions().get(i).id();
            answers.add(new SubmitGameDto.AnswerDto(questionId,
                    i < correct ? data.correctChoice(questionId) : data.wrongChoice(questionId),
                    100L * (i + 1)));
        }
        gameService.submit(player.getId(), new SubmitGameDto(game.sessionId(), answers, null));
    }

    private void close(Long quizId) {
        var quiz = quizRepository.findById(quizId).orElseThrow();
        quizService.triggerClosing(quizId, quiz.getMinScoreThreshold() + 1);
        quizService.moveToComputing(quizId);
    }
}
