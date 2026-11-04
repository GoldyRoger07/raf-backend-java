package com.raikiservice.raf.notification.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.notification.domain.Announcement;
import com.raikiservice.raf.notification.domain.AnnouncementAudience;
import com.raikiservice.raf.notification.domain.AnnouncementRecipient;
import com.raikiservice.raf.notification.domain.AnnouncementStatus;
import com.raikiservice.raf.notification.domain.NotificationChannel;
import com.raikiservice.raf.notification.domain.NotificationType;
import com.raikiservice.raf.notification.domain.RecipientStatus;
import com.raikiservice.raf.notification.repository.AnnouncementRecipientRepository;
import com.raikiservice.raf.notification.repository.AnnouncementRepository;
import com.raikiservice.raf.notification.service.NotificationDelivery.DeliveryOptions;
import com.raikiservice.raf.notification.service.NotificationDelivery.DeliveryResult;
import com.raikiservice.raf.notification.service.NotificationMessage.Delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Envoi des messages du back-office.
 *
 * <p>Deux etapes, chacune reprenable :
 * <ol>
 *   <li><strong>Depart</strong> : a sa date, l'annonce passe de SCHEDULED a SENDING
 *       et ses destinataires sont figes dans {@code announcement_recipients}, dans
 *       la meme transaction. Un joueur inscrit pendant l'envoi ne le recoit pas :
 *       un envoi a tous reste ainsi borne et mesurable.
 *   <li><strong>Remise</strong> : les destinataires PENDING sont traites par lots,
 *       chacun marque DONE, SKIPPED ou FAILED. Apres un redemarrage, l'envoi reprend
 *       aux destinataires restants ; au pire, celui qui etait en cours au moment de
 *       l'arret recoit le message deux fois.
 * </ol>
 *
 * <p>Le travail tourne sur un thread dedie, jamais sur celui des ordonnanceurs :
 * dix mille emails ne doivent pas retarder le demarrage d'un quiz. Le verrou rend
 * les passages sequentiels (declenchement par l'ordonnanceur ou juste apres la
 * creation d'un envoi immediat). Comme les autres ordonnanceurs, suppose une
 * instance unique.
 */
@Slf4j
@Component
public class AnnouncementDispatcher implements DisposableBean {

    static final String FIRST_NAME_VARIABLE = "{prenom}";
    private static final int BATCH_SIZE = 100;
    private static final int MAX_TITLE = 160;
    private static final int MAX_BODY = 1000;

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final NotificationDelivery notificationDelivery;
    private final NotificationPreferenceService preferenceService;
    private final TransactionTemplate transactionTemplate;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "announcements");
        thread.setDaemon(true);
        return thread;
    });

    public AnnouncementDispatcher(AnnouncementRepository announcementRepository,
            AnnouncementRecipientRepository recipientRepository, UserRepository userRepository,
            NotificationDelivery notificationDelivery, NotificationPreferenceService preferenceService,
            PlatformTransactionManager transactionManager) {
        this.announcementRepository = announcementRepository;
        this.recipientRepository = recipientRepository;
        this.userRepository = userRepository;
        this.notificationDelivery = notificationDelivery;
        this.preferenceService = preferenceService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Demande un passage sur le thread des annonces ; sans effet si un passage est deja en attente. */
    public void trigger() {
        if (queued.compareAndSet(false, true)) {
            executor.execute(() -> {
                queued.set(false);
                try {
                    runOnce();
                } catch (RuntimeException e) {
                    log.error("Passage d'envoi des annonces interrompu", e);
                }
            });
        }
    }

    /** Fait partir les annonces arrivees a echeance et remet celles en cours. */
    public void runOnce() {
        lock.lock();
        try {
            for (Long id : announcementRepository.findDueIds(Instant.now())) {
                start(id);
            }
            for (Long id : announcementRepository.findSendingIds()) {
                send(id);
            }
        } finally {
            lock.unlock();
        }
    }

    private void start(Long id) {
        transactionTemplate.executeWithoutResult(status -> {
            if (announcementRepository.start(id, AnnouncementStatus.SCHEDULED,
                    AnnouncementStatus.SENDING, Instant.now()) == 0) {
                return; // annulee entre-temps
            }
            Announcement announcement = announcementRepository.findById(id).orElseThrow();
            if (announcement.getAudience() == AnnouncementAudience.ALL_PLAYERS) {
                recipientRepository.freezeAllPlayers(id, announcement.isIncludeTest());
            } else {
                AnnouncementRecipient recipient = new AnnouncementRecipient();
                recipient.setAnnouncementId(id);
                recipient.setUserId(announcement.getTargetUserId());
                recipientRepository.save(recipient);
            }
        });
    }

    private void send(Long id) {
        Announcement announcement = announcementRepository.findById(id).orElse(null);
        if (announcement == null) {
            return;
        }
        while (true) {
            List<AnnouncementRecipient> batch = recipientRepository.findByAnnouncementIdAndStatusOrderByIdAsc(
                    id, RecipientStatus.PENDING, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            Map<Long, User> users = userRepository.findAllById(
                    batch.stream().map(AnnouncementRecipient::getUserId).toList()).stream()
                    .collect(Collectors.toMap(User::getId, Function.identity()));
            for (AnnouncementRecipient recipient : batch) {
                deliver(announcement, recipient, users.get(recipient.getUserId()));
            }
        }
        transactionTemplate.executeWithoutResult(status ->
                announcementRepository.complete(id, Instant.now()));
    }

    private void deliver(Announcement announcement, AnnouncementRecipient recipient, User user) {
        try {
            if (user == null || user.getStatus() == UserStatus.DELETED) {
                recipient.setStatus(RecipientStatus.SKIPPED);
            } else {
                DeliveryResult result = notificationDelivery.deliver(
                        new Delivery(user.getId(), message(announcement.getId(), announcement.getTitle(),
                                announcement.getBody(), announcement.getLinkPath(), user.getFirstName())),
                        new DeliveryOptions(channelsFor(announcement, user), announcement.isChannelPopup(),
                                announcement.isChannelPopup() ? announcement.getPopupExpiresAt() : null));
                recipient.setStatus(result.skipped() ? RecipientStatus.SKIPPED : RecipientStatus.DONE);
                recipient.setNotificationId(result.notificationId());
                recipient.setEmailSent(result.emailSent());
                recipient.setPushSent(result.pushSent());
            }
        } catch (RuntimeException e) {
            // Un destinataire en echec ne bloque pas les suivants.
            log.error("Annonce {} non remise a l'utilisateur {}", announcement.getId(),
                    recipient.getUserId(), e);
            recipient.setStatus(RecipientStatus.FAILED);
        }
        recipient.setProcessedAt(Instant.now());
        recipientRepository.save(recipient);
    }

    /**
     * Canaux choisis par l'auteur, restreints a ce que le joueur accepte (sauf message
     * important) et, pour l'email, a une adresse verifiee : une adresse jamais confirmee
     * est peut-etre celle d'un autre.
     */
    private Set<NotificationChannel> channelsFor(Announcement announcement, User user) {
        Set<NotificationChannel> accepted = announcement.isImportant()
                ? EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.PUSH)
                : preferenceService.enabledChannels(user.getId(), NotificationType.ANNOUNCEMENT);

        Set<NotificationChannel> channels = EnumSet.noneOf(NotificationChannel.class);
        if (announcement.isChannelPush() && accepted.contains(NotificationChannel.PUSH)) {
            channels.add(NotificationChannel.PUSH);
        }
        if (announcement.isChannelEmail() && user.isEmailVerified()
                && accepted.contains(NotificationChannel.EMAIL)) {
            channels.add(NotificationChannel.EMAIL);
        }
        return channels;
    }

    /** Contenu personnalise pour un destinataire ({@code {prenom}} remplace). */
    static NotificationMessage message(Long announcementId, String title, String body,
            String linkPath, String firstName) {
        String name = firstName == null ? "" : firstName;
        Map<String, Object> data = new LinkedHashMap<>();
        if (announcementId != null) {
            data.put("announcementId", announcementId);
        }
        if (linkPath != null) {
            data.put("path", linkPath);
        }
        return new NotificationMessage(NotificationType.ANNOUNCEMENT,
                truncate(title.replace(FIRST_NAME_VARIABLE, name), MAX_TITLE),
                truncate(body.replace(FIRST_NAME_VARIABLE, name), MAX_BODY),
                data, linkPath);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
