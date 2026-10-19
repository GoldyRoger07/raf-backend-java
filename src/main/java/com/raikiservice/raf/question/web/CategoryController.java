package com.raikiservice.raf.question.web;

import java.util.List;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.question.service.CategoryService;
import com.raikiservice.raf.question.web.dto.CategoryDto;
import com.raikiservice.raf.question.web.dto.CategoryResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Categories.
 *
 * <p>La lecture est ouverte a tout joueur connecte : les categories servent a
 * presenter les quiz et les sujets soumis au vote. L'ecriture reste reservee aux
 * moderateurs.
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@Tag(name = "Categories")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @Operation(summary = "Lister les categories actives")
    public List<CategoryResponse> listActive() {
        return categoryService.listActive();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter une categorie")
    public CategoryResponse get(@PathVariable Long id) {
        return categoryService.get(id);
    }

    @GetMapping("/all")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Lister toutes les categories, actives ou non")
    public List<CategoryResponse> listAll() {
        return categoryService.listAll();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Creer une categorie")
    public ResponseEntity<CategoryResponse> create(@AuthenticationPrincipal UserPrincipal actor,
            @Valid @RequestBody CategoryDto request) {

        CategoryResponse response = categoryService.create(
                actor.getId(), request.name(), request.description(),
                request.parentId(), request.mediaId());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Modifier une categorie")
    public CategoryResponse update(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id, @Valid @RequestBody CategoryDto request) {

        return categoryService.update(
                actor.getId(), id, request.name(), request.description(),
                request.parentId(), request.mediaId(), request.active());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Supprimer une categorie vide")
    public MessageResponse delete(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        categoryService.delete(actor.getId(), id);
        return new MessageResponse("Categorie supprimee.");
    }
}
