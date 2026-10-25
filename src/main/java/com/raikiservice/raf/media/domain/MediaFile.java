package com.raikiservice.raf.media.domain;

import com.raikiservice.raf.common.domain.BaseEntity;
import com.raikiservice.raf.media.service.MediaUrls;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Image stockee sur le disque du serveur.
 *
 * <p>{@code storageKey} est le chemin du fichier dans le dossier des medias
 * ({@code 2026/10/<uuid>.jpg}) : l'URL publique en est deduite a la lecture,
 * pour qu'un changement de domaine ne touche pas la base.
 */
@Getter
@Setter
@Entity
@Table(name = "media_files")
public class MediaFile extends BaseEntity {

    @Column(name = "storage_key", nullable = false, length = 500, unique = true)
    private String storageKey;

    @Column(name = "thumbnail_key", length = 500)
    private String thumbnailKey;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    private Integer width;

    private Integer height;

    @Column(length = 120)
    private String folder;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    public String getUrl() {
        return MediaUrls.resolve(storageKey);
    }

    public String getThumbnailUrl() {
        return MediaUrls.resolve(thumbnailKey);
    }
}
