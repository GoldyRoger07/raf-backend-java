package com.raikiservice.raf.notification.domain;

import java.util.EnumSet;
import java.util.Set;

import lombok.Getter;

/**
 * Types de notification et leurs canaux par defaut (en plus de l'in-app, toujours
 * enregistre).
 *
 * <p>L'email est reserve a ce qui touche a l'argent ou a ce que le joueur doit
 * savoir meme hors de l'application. Les evenements de masse (debut, cloture d'un
 * quiz) passent par le push : un email a chaque detenteur de ticket serait du
 * bruit, et couterait cher.
 */
@Getter
public enum NotificationType {

    QUIZ_SCHEDULED("Demarrage programme d'un quiz", EnumSet.of(NotificationChannel.PUSH)),
    QUIZ_STARTED("Un quiz a demarre", EnumSet.of(NotificationChannel.PUSH)),
    QUIZ_CLOSING("Compte a rebours de cloture", EnumSet.of(NotificationChannel.PUSH)),
    QUIZ_CANCELLED("Quiz annule et tickets rembourses",
            EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.PUSH)),
    QUIZ_RESULTS("Resultats d'un quiz", EnumSet.of(NotificationChannel.PUSH)),
    PRIZE_WON("Gain remporte", EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.PUSH)),
    TOPUP_CONFIRMED("Recharge confirmee", EnumSet.of(NotificationChannel.PUSH)),
    WITHDRAWAL_APPROVED("Retrait approuve", EnumSet.of(NotificationChannel.PUSH)),
    WITHDRAWAL_PAID("Retrait verse", EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.PUSH)),
    WITHDRAWAL_REJECTED("Retrait refuse",
            EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.PUSH)),
    /** Reserve aux administrateurs. */
    ADMIN_RECONCILIATION_ALERT("Alerte du rapprochement comptable",
            EnumSet.of(NotificationChannel.EMAIL));

    private final String description;
    private final Set<NotificationChannel> defaultChannels;

    NotificationType(String description, Set<NotificationChannel> defaultChannels) {
        this.description = description;
        this.defaultChannels = defaultChannels;
    }
}
