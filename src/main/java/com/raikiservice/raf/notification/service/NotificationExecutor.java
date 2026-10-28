package com.raikiservice.raf.notification.service;

import com.raikiservice.raf.common.async.AfterTransactionExecutor;

import org.springframework.stereotype.Component;

/**
 * File dediee aux notifications, separee de celle de l'audit : prevenir dix mille
 * detenteurs de ticket qu'un quiz demarre (push, emails par SMTP) prend du temps,
 * et ne doit retarder ni les traces d'audit ni les requetes.
 */
@Component
public class NotificationExecutor extends AfterTransactionExecutor {

    public NotificationExecutor() {
        super("notification-", 2, 1_000);
    }
}
