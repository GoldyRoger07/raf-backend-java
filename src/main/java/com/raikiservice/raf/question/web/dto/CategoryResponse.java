package com.raikiservice.raf.question.web.dto;

import com.raikiservice.raf.question.domain.Category;

public record CategoryResponse(
        Long id,
        String name,
        String slug,
        String description,
        Long parentId,
        String imageUrl,
        /** Pour que le back-office garde l'image en modifiant la categorie. */
        Long mediaId,
        boolean active,
        boolean groupEnabled,
        boolean soloEnabled) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getParent() == null ? null : category.getParent().getId(),
                category.getMedia() == null ? null : category.getMedia().getUrl(),
                category.getMedia() == null ? null : category.getMedia().getId(),
                category.isActive(),
                category.isGroupEnabled(),
                category.isSoloEnabled());
    }
}
