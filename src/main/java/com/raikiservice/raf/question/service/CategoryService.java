package com.raikiservice.raf.question.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.media.service.MediaService;
import com.raikiservice.raf.question.domain.Category;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.repository.CategoryRepository;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.web.dto.CategoryResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final QuestionRepository questionRepository;
    private final MediaService mediaService;
    private final AuditService auditService;

    /** Les relations parent et media sont paresseuses : on convertit dans la transaction. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> listActive() {
        return categoryRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> listAll() {
        return categoryRepository.findAll().stream().map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(Long id) {
        return CategoryResponse.from(require(id));
    }

    @Transactional(readOnly = true)
    public Category require(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Categorie"));
    }

    @Transactional
    public CategoryResponse create(Long actorId, String name, String description, Long parentId,
            Long mediaId) {
        String slug = slugify(name);
        if (categoryRepository.existsBySlug(slug)) {
            throw new ApiException(ErrorCode.CATEGORY_SLUG_TAKEN);
        }

        Category category = new Category();
        apply(category, name, slug, description, parentId, mediaId);

        Category saved = categoryRepository.save(category);
        auditService.record(actorId, "CATEGORY_CREATED", "Category",
                String.valueOf(saved.getId()), null, name);
        return CategoryResponse.from(saved);
    }

    @Transactional
    public CategoryResponse update(Long actorId, Long id, String name, String description,
            Long parentId, Long mediaId, Boolean active) {
        Category category = require(id);
        String previous = category.getName();

        String slug = slugify(name);
        if (!slug.equals(category.getSlug()) && categoryRepository.existsBySlug(slug)) {
            throw new ApiException(ErrorCode.CATEGORY_SLUG_TAKEN);
        }

        apply(category, name, slug, description, parentId, mediaId);
        if (active != null) {
            category.setActive(active);
        }

        auditService.record(actorId, "CATEGORY_UPDATED", "Category", String.valueOf(id),
                previous, name);
        return CategoryResponse.from(category);
    }

    /**
     * Suppression refusee des qu'une question s'y rattache.
     *
     * <p>Les parties deja jouees referencent leurs questions : effacer la categorie
     * casserait l'historique et donc la justification des classements passes.
     * La bonne action dans ce cas est de la desactiver.
     */
    @Transactional
    public void delete(Long actorId, Long id) {
        Category category = require(id);

        long total = questionRepository.countByCategoryIdAndStatus(id, QuestionStatus.PUBLISHED)
                + questionRepository.countByCategoryIdAndStatus(id, QuestionStatus.DRAFT)
                + questionRepository.countByCategoryIdAndStatus(id, QuestionStatus.ARCHIVED);

        if (total > 0) {
            throw new ApiException(ErrorCode.CATEGORY_HAS_QUESTIONS,
                    "Cette categorie contient " + total + " question(s). Desactivez-la plutot.");
        }
        if (!categoryRepository.findByParentIdOrderByNameAsc(id).isEmpty()) {
            throw new ApiException(ErrorCode.CATEGORY_HAS_QUESTIONS,
                    "Cette categorie a des sous-categories.");
        }

        categoryRepository.delete(category);
        auditService.record(actorId, "CATEGORY_DELETED", "Category", String.valueOf(id),
                category.getName(), null);
    }

    private void apply(Category category, String name, String slug, String description,
            Long parentId, Long mediaId) {
        category.setName(name.trim());
        category.setSlug(slug);
        category.setDescription(description);
        category.setParent(parentId == null ? null : require(parentId));
        category.setMedia(mediaId == null ? null : mediaService.require(mediaId));
    }

    /** "Culture generale d'Haiti" -> "culture-generale-d-haiti". */
    public static String slugify(String value) {
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return normalized.isEmpty() ? "categorie" : normalized;
    }
}
