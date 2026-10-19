package com.raikiservice.raf.media.service;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.media.domain.MediaFile;
import com.raikiservice.raf.media.repository.MediaFileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/svg+xml");

    private final MediaFileRepository mediaFileRepository;
    private final ImageKitClient imageKitClient;
    private final AuditService auditService;

    public Map<String, Object> authenticationParameters() {
        return imageKitClient.authenticationParameters();
    }

    /** Televersement cote serveur, puis enregistrement de la reference. */
    @Transactional
    public MediaFile upload(Long userId, MultipartFile file, String folder) {
        validate(file);

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.IMPORT_FILE_INVALID, "Fichier illisible.");
        }

        ImageKitClient.UploadResult result = imageKitClient.upload(
                content, file.getOriginalFilename(), folder);

        MediaFile media = new MediaFile();
        media.setImagekitFileId(result.fileId());
        media.setUrl(result.url());
        media.setThumbnailUrl(result.thumbnailUrl());
        media.setFilePath(result.filePath());
        media.setMimeType(file.getContentType());
        media.setSizeBytes(file.getSize());
        media.setFolder(folder);
        media.setUploadedBy(userId);

        return mediaFileRepository.save(media);
    }

    /**
     * Enregistre une reference apres un televersement effectue par le frontend.
     *
     * <p>Les donnees viennent du client : on ne fait confiance qu'a l'identifiant
     * ImageKit, dont l'unicite est garantie en base. Un meme fichier enregistre
     * deux fois renvoie la ligne existante plutot que d'en creer une seconde.
     */
    @Transactional
    public MediaFile register(Long userId, String imagekitFileId, String url, String thumbnailUrl,
            String filePath, String mimeType, Long sizeBytes, String folder) {

        return mediaFileRepository.findByImagekitFileId(imagekitFileId).orElseGet(() -> {
            MediaFile media = new MediaFile();
            media.setImagekitFileId(imagekitFileId);
            media.setUrl(url);
            media.setThumbnailUrl(thumbnailUrl);
            media.setFilePath(filePath);
            media.setMimeType(mimeType);
            media.setSizeBytes(sizeBytes);
            media.setFolder(folder);
            media.setUploadedBy(userId);
            return mediaFileRepository.save(media);
        });
    }

    @Transactional(readOnly = true)
    public MediaFile require(Long mediaId) {
        return mediaFileRepository.findById(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media"));
    }

    /**
     * Supprime la reference et tente la suppression chez ImageKit.
     *
     * <p>Les colonnes qui pointent vers un media sont en ON DELETE SET NULL : une
     * question dont l'image est supprimee reste valide, elle perd juste son visuel.
     */
    @Transactional
    public void delete(Long actorId, Long mediaId) {
        MediaFile media = require(mediaId);

        boolean removedRemotely = imageKitClient.delete(media.getImagekitFileId());
        mediaFileRepository.delete(media);

        auditService.record(actorId, "MEDIA_DELETED", "MediaFile", String.valueOf(mediaId),
                media.getUrl(), removedRemotely ? "supprime chez ImageKit"
                        : "ORPHELIN chez ImageKit : suppression distante echouee");
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_FILE_INVALID, "Aucun fichier fourni.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ApiException(ErrorCode.MEDIA_TOO_LARGE,
                    "Taille maximale : " + (MAX_SIZE_BYTES / 1024 / 1024) + " Mo.");
        }
        if (file.getContentType() == null || !ALLOWED_TYPES.contains(file.getContentType())) {
            throw new ApiException(ErrorCode.MEDIA_TYPE_NOT_ALLOWED,
                    "Types acceptes : JPEG, PNG, WebP, GIF, SVG.");
        }
    }
}
