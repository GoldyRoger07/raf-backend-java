package com.raikiservice.raf.media.web;

import java.util.Map;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.media.service.MediaService;
import com.raikiservice.raf.media.web.dto.MediaResponse;
import com.raikiservice.raf.media.web.dto.RegisterMediaDto;

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
import org.springframework.web.bind.annotation.RequestBody;
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
     * Signature courte permettant au frontend de televerser directement vers
     * ImageKit. La cle privee ne quitte jamais le serveur.
     */
    @GetMapping("/auth-params")
    @Operation(summary = "Obtenir les parametres de televersement cote client")
    public Map<String, Object> authParams() {
        return mediaService.authenticationParameters();
    }

    @PostMapping
    @Operation(summary = "Enregistrer une image deja televersee par le frontend")
    public ResponseEntity<MediaResponse> register(@AuthenticationPrincipal UserPrincipal actor,
            @Valid @RequestBody RegisterMediaDto request) {

        MediaResponse response = MediaResponse.from(mediaService.register(
                actor.getId(), request.imagekitFileId(), request.url(), request.thumbnailUrl(),
                request.filePath(), request.mimeType(), request.sizeBytes(), request.folder()));

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Televerser une image via le backend")
    public ResponseEntity<MediaResponse> upload(@AuthenticationPrincipal UserPrincipal actor,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "raf") String folder) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MediaResponse.from(mediaService.upload(actor.getId(), file, folder)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter une image")
    public MediaResponse get(@PathVariable Long id) {
        return MediaResponse.from(mediaService.require(id));
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
