package com.raikiservice.raf.media.service;

import java.io.IOException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.media.domain.MediaFile;
import com.raikiservice.raf.media.repository.MediaFileRepository;
import com.raikiservice.raf.media.web.dto.MediaResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Images televersees par le back-office, stockees sur le serveur.
 *
 * <p>Fichier et ligne en base doivent aller ensemble : un fichier sans ligne est
 * un orphelin qui occupe le disque, une ligne sans fichier une image cassee.
 * L'ecriture precede donc l'insertion et s'annule avec la transaction ; la
 * suppression du fichier attend que celle de la ligne soit validee.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    /** Simple etiquette de classement : n'entre jamais dans le chemin du fichier. */
    private static final Pattern FOLDER = Pattern.compile("[a-z0-9-]{1,40}");

    private final MediaFileRepository mediaFileRepository;
    private final MediaStorage storage;
    private final ImageProcessor imageProcessor;
    private final MediaUsageQueries usageQueries;
    private final AuditService auditService;

    @Transactional
    public MediaFile upload(Long userId, MultipartFile file, String folder) {
        validate(file, folder);

        byte[] source;
        try {
            source = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.IMPORT_FILE_INVALID, "Fichier illisible.");
        }
        ImageProcessor.ProcessedImage image = imageProcessor.process(source);

        // Nom aleatoire : impossible a deviner, et jamais celui fourni par le client.
        String base = YearMonth.now(ZoneOffset.UTC).toString().replace('-', '/')
                + "/" + UUID.randomUUID();
        String key = base + "." + image.extension();
        String thumbnailKey = base + "-thumb." + image.extension();

        storage.store(key, image.content());
        storage.store(thumbnailKey, image.thumbnail());
        deleteIfRolledBack(List.of(key, thumbnailKey));

        MediaFile media = new MediaFile();
        media.setStorageKey(key);
        media.setThumbnailKey(thumbnailKey);
        media.setMimeType(image.mimeType());
        media.setSizeBytes((long) image.content().length);
        media.setWidth(image.width());
        media.setHeight(image.height());
        media.setFolder(folder);
        media.setUploadedBy(userId);
        return mediaFileRepository.save(media);
    }

    /** Les plus recentes d'abord, avec le nombre d'utilisations de chacune. */
    @Transactional(readOnly = true)
    public Page<MediaResponse> list(String folder, Pageable pageable) {
        Page<MediaFile> page = folder == null || folder.isBlank()
                ? mediaFileRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : mediaFileRepository.findByFolderOrderByCreatedAtDescIdDesc(folder, pageable);
        Map<Long, Long> usages = usageQueries.countFor(page.map(MediaFile::getId).getContent());
        return page.map(media -> MediaResponse.from(media, usages.getOrDefault(media.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public List<String> folders() {
        return mediaFileRepository.folders();
    }

    @Transactional(readOnly = true)
    public MediaResponse view(Long mediaId) {
        MediaFile media = require(mediaId);
        return MediaResponse.from(media,
                usageQueries.countFor(List.of(mediaId)).getOrDefault(mediaId, 0L));
    }

    @Transactional(readOnly = true)
    public MediaFile require(Long mediaId) {
        return mediaFileRepository.findById(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media"));
    }

    /**
     * Supprime la reference, puis le fichier une fois la suppression validee.
     *
     * <p>Les colonnes qui pointent vers un media sont en ON DELETE SET NULL : une
     * question dont l'image est supprimee reste valide, elle perd juste son visuel.
     */
    @Transactional
    public void delete(Long actorId, Long mediaId) {
        MediaFile media = require(mediaId);
        mediaFileRepository.delete(media);

        List<String> keys = Stream.of(media.getStorageKey(), media.getThumbnailKey())
                .filter(MediaUrls::isLocal)
                .toList();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                keys.forEach(key -> {
                    if (!storage.delete(key)) {
                        log.warn("Media {} supprime en base, fichier {} ORPHELIN sur le disque",
                                mediaId, key);
                    }
                });
            }
        });

        auditService.record(actorId, "MEDIA_DELETED", "MediaFile", String.valueOf(mediaId),
                media.getStorageKey(), null);
    }

    /** L'insertion a echoue (ou la transaction appelante) : les fichiers ecrits ne servent a rien. */
    private void deleteIfRolledBack(List<String> keys) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    keys.forEach(storage::delete);
                }
            }
        });
    }

    private static void validate(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_FILE_INVALID, "Aucun fichier fourni.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ApiException(ErrorCode.MEDIA_TOO_LARGE,
                    "Taille maximale : " + (MAX_SIZE_BYTES / 1024 / 1024) + " Mo.");
        }
        if (folder == null || !FOLDER.matcher(folder).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Dossier : lettres minuscules, chiffres et tirets (40 au plus).");
        }
    }
}
