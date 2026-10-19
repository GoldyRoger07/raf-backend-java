package com.raikiservice.raf.question.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.service.QuestionImportService;
import com.raikiservice.raf.question.service.QuestionService;
import com.raikiservice.raf.question.web.dto.QuestionDto;
import com.raikiservice.raf.question.web.dto.QuestionResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Gestion de la banque de questions.
 *
 * <p>Ouvert aux moderateurs en plus des administrateurs : alimenter la banque est
 * un travail editorial, qui n'a pas besoin des droits financiers.
 */
@RestController
@RequestMapping("/api/admin/questions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Administration - Questions")
public class AdminQuestionController {

    private final QuestionService questionService;
    private final QuestionImportService questionImportService;

    @PostMapping
    @Operation(summary = "Creer une question")
    public ResponseEntity<QuestionResponse> create(@AuthenticationPrincipal UserPrincipal actor,
            @Valid @RequestBody QuestionDto request) {

        QuestionResponse response = questionService.create(
                actor.getId(), request.categoryId(), request.text(), request.type(),
                request.difficulty(), request.explanation(), request.mediaId(),
                request.choices());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier une question")
    public QuestionResponse update(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id, @Valid @RequestBody QuestionDto request) {

        return questionService.update(
                actor.getId(), id, request.categoryId(), request.text(), request.type(),
                request.difficulty(), request.explanation(), request.mediaId(),
                request.choices());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter une question")
    public QuestionResponse get(@PathVariable Long id) {
        return questionService.get(id);
    }

    @GetMapping
    @Operation(summary = "Lister les questions par statut, ou par categorie")
    public Page<QuestionResponse> list(
            @RequestParam(defaultValue = "PUBLISHED") QuestionStatus status,
            @RequestParam(required = false) Long categoryId,
            @PageableDefault(size = 20) Pageable pageable) {

        return categoryId == null
                ? questionService.byStatus(status, pageable)
                : questionService.byCategory(categoryId, status, pageable);
    }

    @GetMapping("/search")
    @Operation(summary = "Rechercher dans les enonces")
    public Page<QuestionResponse> search(@RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return questionService.search(q, pageable);
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "Publier, remettre en brouillon ou archiver")
    public QuestionResponse changeStatus(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id, @RequestParam QuestionStatus status) {
        return questionService.changeStatus(actor.getId(), id, status);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer une question jamais servie")
    public MessageResponse delete(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        questionService.delete(actor.getId(), id);
        return new MessageResponse("Question supprimee.");
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @Operation(summary = "Importer un CSV de questions dans une categorie")
    public QuestionImportService.ImportReport importCsv(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestParam Long categoryId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean publishDirectly) {

        return questionImportService.importCsv(actor.getId(), categoryId, file, publishDirectly);
    }

    @GetMapping("/pool-status")
    @Operation(summary = "Verifier si le vivier d'une categorie suffit pour un quiz")
    public QuestionService.PoolStatus poolStatus(@RequestParam Long categoryId,
            @RequestParam(defaultValue = "40") int questionsPerGame) {
        return questionService.poolStatus(categoryId, questionsPerGame);
    }
}
