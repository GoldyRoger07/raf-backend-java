package com.raikiservice.raf.media.web.dto;

import java.time.Instant;

import com.raikiservice.raf.media.domain.MediaFile;

public record MediaResponse(
        Long id,
        String url,
        String thumbnailUrl,
        String mimeType,
        Long sizeBytes,
        String folder,
        Instant createdAt) {

    public static MediaResponse from(MediaFile media) {
        return new MediaResponse(
                media.getId(),
                media.getUrl(),
                media.getThumbnailUrl(),
                media.getMimeType(),
                media.getSizeBytes(),
                media.getFolder(),
                media.getCreatedAt());
    }
}
