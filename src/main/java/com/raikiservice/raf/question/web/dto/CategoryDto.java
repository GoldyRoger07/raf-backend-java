package com.raikiservice.raf.question.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryDto(

        @NotBlank(message = "Le nom est obligatoire.")
        @Size(max = 120)
        String name,

        @Size(max = 500)
        String description,

        Long parentId,

        Long mediaId,

        Boolean active) {
}
