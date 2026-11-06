package com.raikiservice.raf.news.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Echanges des actualites (back-office et app joueur). */
public final class NewsDtos {

    private NewsDtos() {
    }

    /** Une actualite telle que la voit le back-office. */
    public record NewsResponse(
            Long id,
            String title,
            String body,
            Long mediaId,
            String imageUrl,
            boolean pinned,
            boolean published,
            /** Premiere publication, null pour un brouillon jamais publie. */
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** Une actualite publiee, telle que la voit le joueur. */
    public record NewsItem(
            Long id,
            String title,
            String body,
            String imageUrl,
            boolean pinned,
            Instant publishedAt) {
    }

    /** Creation ou modification d'une actualite. */
    public record NewsRequest(
            @NotBlank(message = "Le titre est obligatoire.")
            @Size(max = 140, message = "Le titre fait au plus 140 caracteres.")
            String title,

            @NotBlank(message = "Le texte est obligatoire.")
            @Size(max = 5000, message = "Le texte fait au plus 5000 caracteres.")
            String body,

            /** Image facultative, choisie dans la mediatheque. */
            Long mediaId,

            boolean pinned,

            boolean published) {
    }
}
