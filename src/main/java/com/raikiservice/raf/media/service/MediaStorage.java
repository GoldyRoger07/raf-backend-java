package com.raikiservice.raf.media.service;

/**
 * Ou vivent les fichiers. Une seule implementation aujourd'hui (le disque du
 * serveur, {@link LocalMediaStorage}) ; un stockage compatible S3 n'aurait qu'a
 * implementer ces deux operations.
 */
public interface MediaStorage {

    /** Ecrit le fichier sous {@code key} ; une ecriture interrompue ne laisse rien de lisible. */
    void store(String key, byte[] content);

    /** Supprime le fichier ; renvoie false s'il n'a pas pu l'etre (il reste alors orphelin). */
    boolean delete(String key);
}
