package com.raikiservice.raf.media.service;

import com.raikiservice.raf.common.config.AppProperties;

import org.springframework.stereotype.Component;

/**
 * Adresse publique d'un media a partir de son chemin de stockage.
 *
 * <p>Statique parce que l'entite {@code MediaFile} en a besoin pour exposer son
 * URL, et qu'une entite JPA n'est pas un bean Spring. L'adresse de base est
 * posee une fois au demarrage, depuis {@code app.media.public-url}.
 */
@Component
public class MediaUrls {

    private static volatile String baseUrl = "";

    public MediaUrls(AppProperties properties) {
        String configured = properties.media().publicUrl();
        baseUrl = configured == null ? "" : configured.replaceAll("/+$", "");
    }

    /**
     * Une valeur deja absolue (ligne heritee d'ImageKit) est rendue telle quelle :
     * l'image reste affichee tant que l'hebergeur la sert.
     */
    public static String resolve(String storageKey) {
        if (storageKey == null) {
            return null;
        }
        if (storageKey.startsWith("http://") || storageKey.startsWith("https://")) {
            return storageKey;
        }
        return baseUrl + "/" + storageKey;
    }

    static boolean isLocal(String storageKey) {
        return storageKey != null && !storageKey.startsWith("http://")
                && !storageKey.startsWith("https://");
    }
}
