package com.raikiservice.raf.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.notification.domain.Announcement;
import com.raikiservice.raf.notification.domain.AnnouncementAudience;
import com.raikiservice.raf.notification.domain.NotificationChannel;
import com.raikiservice.raf.notification.repository.AnnouncementRecipientRepository;
import com.raikiservice.raf.notification.repository.AnnouncementRecipientRepository.AudienceCounts;
import com.raikiservice.raf.notification.repository.AnnouncementRecipientRepository.RecipientStats;
import com.raikiservice.raf.notification.repository.AnnouncementRepository;
import com.raikiservice.raf.notification.repository.PushSubscriptionRepository;
import com.raikiservice.raf.notification.service.NotificationDelivery.DeliveryOptions;
import com.raikiservice.raf.notification.service.NotificationDelivery.DeliveryResult;
import com.raikiservice.raf.notification.service.NotificationMessage.Delivery;
import com.raikiservice.raf.notification.web.dto.AnnouncementRequest;
import com.raikiservice.raf.notification.web.dto.AnnouncementResponse;
import com.raikiservice.raf.notification.web.dto.AnnouncementResponse.Person;
import com.raikiservice.raf.notification.web.dto.AnnouncementResponse.Stats;
import com.raikiservice.raf.notification.web.dto.AnnouncementTestRequest;
import com.raikiservice.raf.notification.web.dto.AnnouncementTestResponse;
import com.raikiservice.raf.notification.web.dto.AudienceResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Messages du back-office : un joueur ou tous, tout de suite ou programmes, par
 * popup dans l'app, push et/ou email. L'envoi lui-meme est fait par
 * {@link AnnouncementDispatcher}.
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    /** Popup sans date de fin choisie : visible une semaine. */
    static final Duration DEFAULT_POPUP_LIFETIME = Duration.ofDays(7);
    /** Tolerance sur une date d'envoi "maintenant" saisie quelques instants plus tot. */
    private static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);
    private static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(365);

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementRecipientRepository recipientRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final UserRepository userRepository;
    private final NotificationDelivery notificationDelivery;
    private final AnnouncementDispatcher dispatcher;
    private final AuditService auditService;

    @Transactional
    public AnnouncementResponse create(Long actorId, boolean superAdmin, AnnouncementRequest request) {
        if (!request.popup() && !request.push() && !request.email()) {
            throw invalid("Choisissez au moins un moyen d'envoi : popup, push ou email.");
        }

        Long targetUserId = null;
        if (request.audience() == AnnouncementAudience.ALL_PLAYERS) {
            if (!superAdmin) {
                throw new ApiException(ErrorCode.ANNOUNCEMENT_ALL_PLAYERS_FORBIDDEN);
            }
        } else {
            targetUserId = activeTarget(request.targetUserId()).getId();
        }

        Instant now = Instant.now();
        Instant scheduledAt = request.scheduledAt() == null ? now : request.scheduledAt();
        if (scheduledAt.isBefore(now.minus(PAST_TOLERANCE))) {
            throw invalid("La date d'envoi est passee.");
        }
        if (scheduledAt.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
            throw invalid("La date d'envoi est trop lointaine (un an au plus).");
        }
        if (scheduledAt.isBefore(now)) {
            scheduledAt = now;
        }

        Instant popupExpiresAt = null;
        if (request.popup()) {
            popupExpiresAt = request.popupExpiresAt() == null
                    ? scheduledAt.plus(DEFAULT_POPUP_LIFETIME) : request.popupExpiresAt();
            if (!popupExpiresAt.isAfter(scheduledAt)) {
                throw invalid("La fin d'affichage du popup doit suivre la date d'envoi.");
            }
        }

        Announcement announcement = new Announcement();
        announcement.setTitle(request.title().trim());
        announcement.setBody(request.body().trim());
        announcement.setLinkPath(blankToNull(request.linkPath()));
        announcement.setAudience(request.audience());
        announcement.setTargetUserId(targetUserId);
        announcement.setIncludeTest(request.audience() == AnnouncementAudience.ALL_PLAYERS
                && request.includeTest());
        announcement.setImportant(request.important());
        announcement.setChannelPopup(request.popup());
        announcement.setChannelPush(request.push());
        announcement.setChannelEmail(request.email());
        announcement.setScheduledAt(scheduledAt);
        announcement.setPopupExpiresAt(popupExpiresAt);
        announcement.setCreatedBy(actorId);
        announcementRepository.save(announcement);

        auditService.record(actorId, "ANNOUNCEMENT_CREATED", "Announcement",
                String.valueOf(announcement.getId()), null, summary(announcement));

        if (!scheduledAt.isAfter(now)) {
            // Envoi immediat : sans attendre le prochain passage de l'ordonnanceur.
            afterCommit(dispatcher::trigger);
        }
        return toResponse(announcement, Stats.EMPTY, people(ids(actorId, targetUserIdOr(announcement))));
    }

    /** Annule un message programme qui n'est pas encore parti. */
    @Transactional
    public AnnouncementResponse cancel(Long actorId, boolean superAdmin, Long id) {
        Announcement announcement = find(id);
        if (announcement.getAudience() == AnnouncementAudience.ALL_PLAYERS && !superAdmin) {
            throw new ApiException(ErrorCode.ANNOUNCEMENT_ALL_PLAYERS_FORBIDDEN);
        }
        if (announcementRepository.cancel(id, actorId, Instant.now()) == 0) {
            throw new ApiException(ErrorCode.ANNOUNCEMENT_NOT_CANCELLABLE);
        }
        auditService.record(actorId, "ANNOUNCEMENT_CANCELLED", "Announcement", String.valueOf(id),
                announcement.getStatus().name(), "CANCELLED");
        return get(id);
    }

    @Transactional(readOnly = true)
    public Page<AnnouncementResponse> list(Pageable pageable) {
        Page<Announcement> page = announcementRepository.findAllByOrderByCreatedAtDescIdDesc(pageable);
        List<Long> ids = page.getContent().stream().map(Announcement::getId).toList();
        Map<Long, Stats> stats = stats(ids);

        Set<Long> userIds = new HashSet<>();
        page.getContent().forEach(a -> {
            userIds.add(a.getCreatedBy());
            userIds.add(targetUserIdOr(a));
        });
        Map<Long, Person> people = people(userIds);

        return page.map(a -> toResponse(a, stats.getOrDefault(a.getId(), Stats.EMPTY), people));
    }

    @Transactional(readOnly = true)
    public AnnouncementResponse get(Long id) {
        Announcement announcement = find(id);
        return toResponse(announcement, stats(List.of(id)).getOrDefault(id, Stats.EMPTY),
                people(ids(announcement.getCreatedBy(), targetUserIdOr(announcement))));
    }

    /**
     * Portee d'un envoi, a afficher avant de confirmer. Pour un envoi a tous, elle
     * est calculee maintenant : les destinataires reels sont figes au depart.
     */
    @Transactional(readOnly = true)
    public AudienceResponse audience(AnnouncementAudience audience, Long targetUserId,
            boolean includeTest) {
        if (audience == AnnouncementAudience.ALL_PLAYERS) {
            AudienceCounts counts = recipientRepository.countAllPlayers(includeTest);
            return new AudienceResponse(counts.getTotal().longValue(),
                    counts.getWithEmail().longValue(), counts.getWithPush().longValue());
        }
        User user = activeTarget(targetUserId);
        return new AudienceResponse(1, user.isEmailVerified() ? 1 : 0,
                pushSubscriptionRepository.findByUserId(user.getId()).isEmpty() ? 0 : 1);
    }

    /**
     * Essai sur le compte de l'auteur, tout de suite et sans tenir compte de ses
     * preferences : c'est le rendu qu'il veut voir. Rien n'est enregistre comme annonce.
     */
    public AnnouncementTestResponse sendTest(Long actorId, AnnouncementTestRequest request) {
        if (!request.popup() && !request.push() && !request.email()) {
            throw invalid("Choisissez au moins un moyen d'envoi : popup, push ou email.");
        }
        User actor = userRepository.findById(actorId).orElseThrow(() -> ApiException.notFound("Compte"));

        Set<NotificationChannel> channels = EnumSet.noneOf(NotificationChannel.class);
        if (request.push()) {
            channels.add(NotificationChannel.PUSH);
        }
        if (request.email()) {
            channels.add(NotificationChannel.EMAIL);
        }
        NotificationMessage message = AnnouncementDispatcher.message(null, "[Test] " + request.title().trim(),
                request.body().trim(), blankToNull(request.linkPath()), actor.getFirstName());

        DeliveryResult result = notificationDelivery.deliver(new Delivery(actorId, message),
                new DeliveryOptions(channels, request.popup(),
                        request.popup() ? Instant.now().plus(Duration.ofDays(1)) : null));
        return new AnnouncementTestResponse(request.popup(), result.emailSent(), result.pushSent(),
                actor.getEmail());
    }

    private User activeTarget(Long userId) {
        if (userId == null) {
            throw invalid("Choisissez le joueur destinataire.");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Joueur"));
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ApiException(ErrorCode.ACCOUNT_DELETED);
        }
        return user;
    }

    private Announcement find(Long id) {
        return announcementRepository.findById(id).orElseThrow(() -> ApiException.notFound("Message"));
    }

    private Map<Long, Stats> stats(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return recipientRepository.stats(ids).stream().collect(Collectors.toMap(
                s -> s.getAnnouncementId().longValue(), AnnouncementService::toStats));
    }

    private static Stats toStats(RecipientStats s) {
        return new Stats(n(s.getTotal()), n(s.getPending()), n(s.getDelivered()), n(s.getFailed()),
                n(s.getSkipped()), n(s.getEmailSent()), n(s.getPushSent()), n(s.getAcknowledged()),
                n(s.getOpened()));
    }

    private static long n(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private Map<Long, Person> people(Set<Long> ids) {
        Set<Long> present = ids.stream().filter(id -> id != null && id > 0).collect(Collectors.toSet());
        return userRepository.findAllById(present).stream().collect(Collectors.toMap(User::getId,
                u -> new Person(u.getId(), u.fullName(), u.getEmail()), (a, b) -> a));
    }

    /** Ensemble tolerant les doublons (auteur qui s'ecrit a lui-meme). */
    private static Set<Long> ids(Long... ids) {
        return new HashSet<>(java.util.Arrays.asList(ids));
    }

    /** Identifiant du destinataire, ou 0 pour un envoi a tous (cle neutre des ensembles). */
    private static Long targetUserIdOr(Announcement announcement) {
        return announcement.getTargetUserId() == null ? 0L : announcement.getTargetUserId();
    }

    private static AnnouncementResponse toResponse(Announcement a, Stats stats, Map<Long, Person> people) {
        Function<Long, Person> person = id -> id == null ? null
                : people.getOrDefault(id, new Person(id, null, null));
        return new AnnouncementResponse(a.getId(), a.getTitle(), a.getBody(), a.getLinkPath(),
                a.getAudience(), person.apply(a.getTargetUserId()), a.isIncludeTest(), a.isImportant(),
                a.isChannelPopup(), a.isChannelPush(), a.isChannelEmail(), a.getStatus(),
                a.getScheduledAt(), a.getPopupExpiresAt(), person.apply(a.getCreatedBy()),
                a.getCreatedAt(), a.getStartedAt(), a.getCompletedAt(), a.getCancelledAt(), stats);
    }

    private static String summary(Announcement a) {
        StringBuilder channels = new StringBuilder();
        if (a.isChannelPopup()) {
            channels.append("popup ");
        }
        if (a.isChannelPush()) {
            channels.append("push ");
        }
        if (a.isChannelEmail()) {
            channels.append("email ");
        }
        String target = a.getAudience() == AnnouncementAudience.ALL_PLAYERS ? "tous les joueurs"
                : "joueur " + a.getTargetUserId();
        return a.getTitle() + " | " + target + " | " + channels.toString().trim()
                + (a.isImportant() ? " | important" : "") + " | envoi " + a.getScheduledAt();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
