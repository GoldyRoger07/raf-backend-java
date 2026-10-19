package com.raikiservice.raf.notification.service;

import java.util.Collection;
import java.util.List;

import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.notification.service.NotificationMessage.Delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Point d'entree des notifications, appele par les services metier.
 *
 * <p><strong>Rien ne part avant le commit.</strong> L'envoi est enregistre aupres
 * de la transaction en cours et n'a lieu que si elle est validee : un quiz dont
 * l'annulation echoue ne doit pas annoncer un remboursement. Il se fait ensuite sur
 * le thread des notifications ({@link NotificationExecutor}), si bien qu'un envoi
 * lent (Resend, service push du navigateur) ne retarde jamais la requete.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationExecutor notificationExecutor;
    private final NotificationDelivery notificationDelivery;
    private final UserRepository userRepository;

    public void notifyUser(Long userId, NotificationMessage message) {
        notify(List.of(new Delivery(userId, message)));
    }

    /** Meme message pour plusieurs joueurs (detenteurs de ticket d'un quiz...). */
    public void notifyUsers(Collection<Long> userIds, NotificationMessage message) {
        notify(userIds.stream().distinct().map(id -> new Delivery(id, message)).toList());
    }

    /** Administrateurs et super-administrateurs. */
    public void notifyAdmins(NotificationMessage message) {
        notifyUsers(userRepository.findIdsByRoles(List.of(RoleName.ADMIN, RoleName.SUPER_ADMIN)),
                message);
    }

    /** Messages personnalises (un rang, un montant), envoyes ensemble apres le commit. */
    public void notify(List<Delivery> deliveries) {
        if (deliveries.isEmpty()) {
            return;
        }
        List<Delivery> copy = List.copyOf(deliveries);
        String label = "notifications " + copy.get(0).message().type() + " (" + copy.size() + ")";

        notificationExecutor.afterCommit(label, () -> {
            for (Delivery delivery : copy) {
                try {
                    notificationDelivery.deliver(delivery);
                } catch (RuntimeException e) {
                    // Un destinataire en echec ne prive pas les suivants de leur message.
                    log.error("Notification {} non remise a l'utilisateur {}",
                            delivery.message().type(), delivery.userId(), e);
                }
            }
        });
    }
}
