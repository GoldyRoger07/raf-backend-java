package com.raikiservice.raf.media.web.dto;

import java.time.Instant;

import com.raikiservice.raf.media.domain.MediaFile;

public record MediaResponse(
        Long id,
        String url,
        String thumbnailUrl,
        String mimeType,
        Long sizeBytes,
        Integer width,
        Integer height,
        String folder,
        /** Sujets, questions, choix, bannieres, sujets de vote et avatars qui l'affichent. */
        long usages,
        Instant createdAt) {

    /** Image qui vient d'etre televersee : encore utilisee nulle part. */
    public static MediaResponse from(MediaFile media) {
        return from(media, 0);
    }

    public static MediaResponse from(MediaFile media, long usages) {
        return new MediaResponse(
                media.getId(),
                media.getUrl(),
                media.getThumbnailUrl(),
                media.getMimeType(),
                media.getSizeBytes(),
                media.getWidth(),
                media.getHeight(),
                media.getFolder(),
                usages,
                media.getCreatedAt());
    }
}
