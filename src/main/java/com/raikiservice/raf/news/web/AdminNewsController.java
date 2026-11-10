package com.raikiservice.raf.news.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.news.service.NewsService;
import com.raikiservice.raf.news.web.dto.NewsDtos.NewsRequest;
import com.raikiservice.raf.news.web.dto.NewsDtos.NewsResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Actualites de l'app joueur, gerees par les administrateurs (voir SecurityConfig). */
@RestController
@RequestMapping("/api/admin/news")
@RequiredArgsConstructor
@Tag(name = "Administration - Actualites")
public class AdminNewsController {

    private final NewsService newsService;

    @GetMapping
    @Operation(summary = "Toutes les actualites, brouillons compris",
            description = "q : titre ou texte. Filtres : published, pinned. Tris : title, published, "
                    + "publishedAt, updatedAt.")
    public Page<NewsResponse> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean published,
            @RequestParam(required = false) Boolean pinned,
            @PageableDefault(size = 20) Pageable pageable) {
        return newsService.list(q, published, pinned, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Une actualite")
    public NewsResponse get(@PathVariable Long id) {
        return newsService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Creer une actualite (publiee ou en brouillon)")
    public NewsResponse create(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody NewsRequest request) {
        return newsService.create(admin.getId(), request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier, publier ou depublier une actualite")
    public NewsResponse update(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long id,
            @Valid @RequestBody NewsRequest request) {
        return newsService.update(admin.getId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une actualite")
    public void delete(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long id) {
        newsService.delete(admin.getId(), id);
    }
}
