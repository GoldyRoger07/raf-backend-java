package com.raikiservice.raf.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.notification.domain.AnnouncementAudience;
import com.raikiservice.raf.notification.domain.AnnouncementStatus;
import com.raikiservice.raf.notification.domain.NotificationChannel;
import com.raikiservice.raf.notification.domain.NotificationType;
import com.raikiservice.raf.notification.service.AnnouncementDispatcher;
import com.raikiservice.raf.notification.service.AnnouncementService;
import com.raikiservice.raf.notification.service.NotificationInboxService;
import com.raikiservice.raf.notification.service.NotificationPreferenceService;
import com.raikiservice.raf.notification.web.dto.AnnouncementRequest;
import com.raikiservice.raf.notification.web.dto.AnnouncementResponse;
import com.raikiservice.raf.notification.web.dto.AnnouncementTestRequest;
import com.raikiservice.raf.notification.web.dto.AudienceResponse;
import com.raikiservice.raf.notification.web.dto.NotificationResponse;
import com.raikiservice.raf.notification.web.dto.PreferenceUpdateDto;
import com.raikiservice.raf.notification.web.dto.PushSubscriptionDto;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Messages du back-office : destinataires, canaux, programmation et popup a acquitter. */
class AnnouncementTest extends IntegrationTest {

    @Autowired private AnnouncementService announcementService;
    @Autowired private AnnouncementDispatcher dispatcher;
    @Autowired private NotificationInboxService inboxService;
    @Autowired private NotificationPreferenceService preferenceService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aPlayerGetsThePopupThePushAndTheEmail() {
        User admin = data.admin();
        User player = subscribed(data.player());

        AnnouncementResponse created = announcementService.create(admin.getId(), false,
                toOne(player, true, true, true, false, null));
        dispatcher.runOnce();

        List<NotificationResponse> popups = inboxService.pendingPopups(player.getId());
        assertThat(popups).singleElement().satisfies(popup -> {
            assertThat(popup.type()).isEqualTo(NotificationType.ANNOUNCEMENT);
            assertThat(popup.popup()).isTrue();
            // {prenom} remplace par celui du destinataire.
            assertThat(popup.body()).startsWith("Bonjour " + player.getFirstName() + ",");
            assertThat(popup.data()).contains("/portefeuille");
        });
        assertThat(pushes.to(player.getId())).singleElement().satisfies(push ->
                assertThat(push.payload()).contains("ANNOUNCEMENT").contains("/portefeuille"));
        assertThat(mails.to(player.getEmail())).singleElement().satisfies(mail ->
                assertThat(mail.subject()).startsWith("Maintenance"));

        inboxService.acknowledge(player.getId(), popups.get(0).id());
        inboxService.acknowledge(player.getId(), popups.get(0).id()); // rejoue : sans effet
        assertThat(inboxService.pendingPopups(player.getId())).isEmpty();

        AnnouncementResponse sent = announcementService.get(created.id());
        assertThat(sent.status()).isEqualTo(AnnouncementStatus.SENT);
        assertThat(sent.stats().total()).isEqualTo(1);
        assertThat(sent.stats().delivered()).isEqualTo(1);
        assertThat(sent.stats().emailSent()).isEqualTo(1);
        assertThat(sent.stats().pushSent()).isEqualTo(1);
        assertThat(sent.stats().acknowledged()).isEqualTo(1);
    }

    @Test
    void anotherPlayerCannotAcknowledgeMyPopup() {
        User player = data.player();
        User other = data.player();
        announcementService.create(data.admin().getId(), false, toOne(player, true, false, false, false, null));
        dispatcher.runOnce();

        Long popupId = inboxService.pendingPopups(player.getId()).get(0).id();
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () -> inboxService.acknowledge(other.getId(), popupId));
        assertThat(inboxService.pendingPopups(player.getId())).hasSize(1);
    }

    @Test
    void withoutPopupTheMessageOnlyLandsInTheInbox() {
        User player = data.player();
        announcementService.create(data.admin().getId(), false, toOne(player, false, true, false, false, null));
        dispatcher.runOnce();

        assertThat(inboxService.pendingPopups(player.getId())).isEmpty();
        assertThat(inboxService.list(player.getId(), true, PageRequest.of(0, 10)).getContent())
                .singleElement().satisfies(n -> assertThat(n.popup()).isFalse());
    }

    @Test
    void onlyASuperAdminCanWriteToEveryone() {
        User admin = data.admin();
        assertApiError(ErrorCode.ANNOUNCEMENT_ALL_PLAYERS_FORBIDDEN, () ->
                announcementService.create(admin.getId(), false, toAll(false)));
    }

    @Test
    void everyoneMeansActivePlayersWithoutStaffNorTestAccounts() {
        User superAdmin = data.superAdmin();
        User player = data.player();
        User tester = data.player();
        tester.setTest(true);
        userRepository.save(tester);
        User moderator = data.moderator();

        AudienceResponse audience = announcementService.audience(AnnouncementAudience.ALL_PLAYERS, null, false);
        AnnouncementResponse created = announcementService.create(superAdmin.getId(), true, toAll(false));
        dispatcher.runOnce();

        assertThat(inboxService.pendingPopups(player.getId())).hasSize(1);
        assertThat(inboxService.pendingPopups(tester.getId())).isEmpty();
        assertThat(inboxService.pendingPopups(moderator.getId())).isEmpty();
        assertThat(inboxService.pendingPopups(superAdmin.getId())).isEmpty();

        AnnouncementResponse sent = announcementService.get(created.id());
        assertThat(sent.status()).isEqualTo(AnnouncementStatus.SENT);
        assertThat(sent.stats().total()).isEqualTo(audience.total());
        assertThat(sent.stats().pending()).isZero();
    }

    @Test
    void aScheduledMessageWaitsForItsDateAndCanBeCancelled() {
        User admin = data.admin();
        User player = data.player();
        AnnouncementResponse scheduled = announcementService.create(admin.getId(), false,
                toOne(player, true, false, false, false, Instant.now().plus(Duration.ofHours(1))));

        dispatcher.runOnce();
        assertThat(inboxService.pendingPopups(player.getId())).isEmpty();
        assertThat(announcementService.get(scheduled.id()).status()).isEqualTo(AnnouncementStatus.SCHEDULED);

        assertThat(announcementService.cancel(admin.getId(), false, scheduled.id()).status())
                .isEqualTo(AnnouncementStatus.CANCELLED);
        assertApiError(ErrorCode.ANNOUNCEMENT_NOT_CANCELLABLE, () ->
                announcementService.cancel(admin.getId(), false, scheduled.id()));

        // Arrivee a echeance, une annonce annulee ne part pas.
        jdbc.update("update announcements set scheduled_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scheduled.id());
        dispatcher.runOnce();
        assertThat(inboxService.pendingPopups(player.getId())).isEmpty();
    }

    @Test
    void aSentMessageCannotBeCancelled() {
        User admin = data.admin();
        AnnouncementResponse created = announcementService.create(admin.getId(), false,
                toOne(data.player(), true, false, false, false, null));
        dispatcher.runOnce();

        assertApiError(ErrorCode.ANNOUNCEMENT_NOT_CANCELLABLE, () ->
                announcementService.cancel(admin.getId(), false, created.id()));
    }

    @Test
    void playerPreferencesApplyUnlessTheMessageIsImportant() {
        User admin = data.admin();
        User player = subscribed(data.player());
        preferenceService.update(player.getId(), List.of(
                new PreferenceUpdateDto(NotificationType.ANNOUNCEMENT, NotificationChannel.EMAIL, false),
                new PreferenceUpdateDto(NotificationType.ANNOUNCEMENT, NotificationChannel.PUSH, false)));

        announcementService.create(admin.getId(), false, toOne(player, false, true, true, false, null));
        dispatcher.runOnce();
        assertThat(mails.to(player.getEmail())).isEmpty();
        assertThat(pushes.to(player.getId())).isEmpty();

        announcementService.create(admin.getId(), false, toOne(player, false, true, true, true, null));
        dispatcher.runOnce();
        assertThat(mails.to(player.getEmail())).hasSize(1);
        assertThat(pushes.to(player.getId())).hasSize(1);
    }

    @Test
    void anUnverifiedEmailIsNotWrittenTo() {
        User player = data.player();
        player.setEmailVerifiedAt(null);
        userRepository.save(player);

        assertThat(announcementService.audience(AnnouncementAudience.ONE_PLAYER, player.getId(), false)
                .withEmail()).isZero();
        announcementService.create(data.admin().getId(), false, toOne(player, true, false, true, true, null));
        dispatcher.runOnce();

        assertThat(mails.to(player.getEmail())).isEmpty();
        assertThat(inboxService.pendingPopups(player.getId())).hasSize(1);
    }

    @Test
    void anExpiredPopupIsNoLongerShown() {
        User player = data.player();
        announcementService.create(data.admin().getId(), false, toOne(player, true, false, false, false, null));
        dispatcher.runOnce();
        Long id = inboxService.pendingPopups(player.getId()).get(0).id();

        jdbc.update("update notifications set expires_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
        assertThat(inboxService.pendingPopups(player.getId())).isEmpty();
    }

    @Test
    void aMessageNeedsAChannelAndAnInternalLink() {
        User admin = data.admin();
        User player = data.player();
        assertApiError(ErrorCode.VALIDATION_ERROR, () ->
                announcementService.create(admin.getId(), false, toOne(player, false, false, false, false, null)));
        assertApiError(ErrorCode.VALIDATION_ERROR, () ->
                announcementService.create(admin.getId(), false, new AnnouncementRequest(
                        AnnouncementAudience.ONE_PLAYER, player.getId(), false, false, true, false, false,
                        "Titre", "Corps", null, Instant.now().minus(Duration.ofDays(1)), null)));
    }

    @Test
    void aTestIsSentToItsAuthorOnly() {
        User admin = subscribed(data.admin());

        var result = announcementService.sendTest(admin.getId(), new AnnouncementTestRequest(
                true, true, true, "Nouveau quiz", "Bonjour {prenom}", "/quiz/1"));

        assertThat(result.emailSent()).isTrue();
        assertThat(result.pushSent()).isTrue();
        assertThat(inboxService.pendingPopups(admin.getId())).singleElement().satisfies(popup -> {
            assertThat(popup.title()).isEqualTo("[Test] Nouveau quiz");
            assertThat(popup.body()).isEqualTo("Bonjour " + admin.getFirstName());
        });
    }

    private static AnnouncementRequest toOne(User player, boolean popup, boolean push, boolean email,
            boolean important, Instant scheduledAt) {
        return new AnnouncementRequest(AnnouncementAudience.ONE_PLAYER, player.getId(), false, important,
                popup, push, email, "Maintenance ce soir",
                "Bonjour {prenom}, RAF sera indisponible de 22 h a 23 h.", "/portefeuille",
                scheduledAt, null);
    }

    private static AnnouncementRequest toAll(boolean includeTest) {
        return new AnnouncementRequest(AnnouncementAudience.ALL_PLAYERS, null, includeTest, false,
                true, false, false, "Nouveau quiz samedi", "Bonjour {prenom}, rendez-vous samedi a 20 h.",
                null, null, null);
    }

    private User subscribed(User user) {
        inboxService.subscribe(user.getId(), new PushSubscriptionDto(
                "https://push.example.test/" + UUID.randomUUID(),
                new PushSubscriptionDto.Keys("cle-p256dh", "cle-auth")), "junit");
        return user;
    }
}
