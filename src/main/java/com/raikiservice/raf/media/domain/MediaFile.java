package com.raikiservice.raf.media.domain;

import com.raikiservice.raf.common.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Reference vers un fichier heberge sur ImageKit.
 *
 * <p>Le binaire ne transite pas forcement par le backend : le frontend televerse
 * directement vers ImageKit avec une signature obtenue ici, puis enregistre la
 * reference. {@code imagekitFileId} est conserve pour pouvoir supprimer le fichier
 * chez l'hebergeur, sans quoi une suppression en base laisserait un orphelin
 * facture indefiniment.
 */
@Getter
@Setter
@Entity
@Table(name = "media_files")
public class MediaFile extends BaseEntity {

    @Column(name = "imagekit_file_id", nullable = false, length = 120, unique = true)
    private String imagekitFileId;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 120)
    private String folder;

    @Column(name = "uploaded_by")
    private Long uploadedBy;
}
