package com.raikiservice.raf.notification.push;

import com.raikiservice.raf.notification.domain.PushSubscription;

/** Envoi d'un push navigateur a un abonnement. */
public interface PushSender {

    enum Result {
        SENT,
        /** Abonnement expire ou revoque par le navigateur : a supprimer. */
        GONE,
        FAILED
    }

    Result send(PushSubscription subscription, String jsonPayload);
}
