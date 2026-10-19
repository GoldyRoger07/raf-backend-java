package com.raikiservice.raf.media.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reference remontee par le frontend apres un televersement direct vers ImageKit. */
public record RegisterMediaDto(

        @NotBlank(message = "L'identifiant ImageKit est obligatoire.")
        @Size(max = 120)
        String imagekitFileId,

        @NotBlank(message = "L'URL est obligatoire.")
        @Size(max = 500)
        String url,

        @Size(max = 500)
        String thumbnailUrl,

        @Size(max = 500)
        String filePath,

        @Size(max = 100)
        String mimeType,

        Long sizeBytes,

        @Size(max = 120)
        String folder) {
}
