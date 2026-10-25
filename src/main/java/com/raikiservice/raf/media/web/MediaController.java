package com.raikiservice.raf.media.web;

import java.util.List;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.media.service.MediaService;
import com.raikiservice.raf.media.web.dto.MediaResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
@Tag(name = "Medias")
public class MediaController {

    private final MediaService mediaService;

    /**
     * L'image est verifiee, reduite et reencodee par le serveur ; la reponse
     * donne son adresse publique et celle de sa miniature.
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Televerser une image (JPEG, PNG, WebP, GIF ; 5 Mo au plus)")
    public ResponseEntity<MediaResponse> upload(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "raf") String folder) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MediaResponse.from(mediaService.upload(actor.getId(), file, folder)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Lister les images, les plus recentes d'abord, eventuellement d'un dossier")
    public Page<MediaResponse> list(@RequestParam(required = false) String folder,
            @PageableDefault(size = 24) Pageable pageable) {
        return mediaService.list(folder, pageable);
    }

    @GetMapping("/folders")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Dossiers utilises par les images")
    public List<String> folders() {
        return mediaService.folders();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter une image")
    public MediaResponse get(@PathVariable Long id) {
        return mediaService.view(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Supprimer une image")
    public MessageResponse delete(@AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {
        mediaService.delete(actor.getId(), id);
        return new MessageResponse("Image supprimee.");
    }
}
