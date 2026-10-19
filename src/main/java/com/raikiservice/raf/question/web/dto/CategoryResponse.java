package com.raikiservice.raf.question.web.dto;

import com.raikiservice.raf.question.domain.Category;

public record CategoryResponse(
        Long id,
        String name,
        String slug,
        String description,
        Long parentId,
        String imageUrl,
        boolean active) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getParent() == null ? null : category.getParent().getId(),
                category.getMedia() == null ? null : category.getMedia().getUrl(),
                category.isActive());
    }
}
