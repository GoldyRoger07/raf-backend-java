package com.raikiservice.raf.media.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fichiers sur le disque du serveur, sous {@code app.media.storage-dir}. En
 * Docker, ce dossier est un volume que nginx sert en lecture seule.
 */
@Slf4j
@Component
public class LocalMediaStorage implements MediaStorage {

    private final Path root;

    public LocalMediaStorage(AppProperties properties) {
        this.root = Path.of(properties.media().storageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Dossier des medias inaccessible : " + root, e);
        }
        log.info("Medias stockes dans {}", root);
    }

    public Path root() {
        return root;
    }

    @Override
    public void store(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            // Ecrit a cote puis renomme : nginx ne sert jamais un fichier a moitie ecrit.
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(temp, content);
                move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            log.error("Ecriture du media {} impossible", key, e);
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_FAILED);
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
            return true;
        } catch (IOException e) {
            log.warn("Suppression du media {} impossible", key, e);
            return false;
        }
    }

    /**
     * Les cles sont generees par le service, jamais par le client ; la
     * verification protege quand meme d'un chemin qui sortirait du dossier.
     */
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Cle de media invalide : " + key);
        }
        return path;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
