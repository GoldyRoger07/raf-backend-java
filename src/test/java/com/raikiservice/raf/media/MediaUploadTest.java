package com.raikiservice.raf.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.media.domain.MediaFile;
import com.raikiservice.raf.media.service.LocalMediaStorage;
import com.raikiservice.raf.media.service.MediaService;
import com.raikiservice.raf.media.web.dto.MediaResponse;
import com.raikiservice.raf.question.service.CategoryService;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Images stockees sur le serveur : format verifie sur le contenu, image reduite
 * et reencodee (sans metadonnees), fichiers et lignes en base qui vont ensemble.
 */
class MediaUploadTest extends IntegrationTest {

    /** WebP 1 x 1 sans perte : ImageIO ne sait pas en ecrire, il faut un fichier tout fait. */
    private static final String TINY_WEBP = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    @Autowired private MediaService mediaService;
    @Autowired private LocalMediaStorage storage;
    @Autowired private MockMvc mvc;
    @Autowired private CategoryService categoryService;

    @Test
    void aLargeTransparentImageIsShrunkKeptAsPngAndGetsAThumbnail() throws Exception {
        MediaFile media = upload(file("logo.png", "image/png", image(2000, 1000, true)));

        assertThat(media.getMimeType()).isEqualTo("image/png");
        assertThat(media.getWidth()).isEqualTo(1600);
        assertThat(media.getHeight()).isEqualTo(800);
        assertThat(media.getStorageKey()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThat(media.getUrl()).isEqualTo("http://media.test/media/" + media.getStorageKey());
        assertThat(media.getThumbnailUrl()).endsWith("-thumb.png");

        BufferedImage thumbnail = ImageIO.read(onDisk(media.getThumbnailKey()).toFile());
        assertThat(thumbnail.getWidth()).isEqualTo(400);
        assertThat(thumbnail.getHeight()).isEqualTo(200);
        assertThat(ImageIO.read(onDisk(media.getStorageKey()).toFile()).getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void aPhotoLosesItsExifMetadata() throws Exception {
        byte[] photo = withExif(image(800, 600, false));
        assertThat(contains(photo, "Exif")).isTrue();

        MediaFile media = upload(file("photo.jpg", "image/jpeg", photo));

        byte[] stored = Files.readAllBytes(onDisk(media.getStorageKey()));
        assertThat(media.getMimeType()).isEqualTo("image/jpeg");
        assertThat(media.getWidth()).isEqualTo(800);
        assertThat(contains(stored, "Exif")).isFalse();
        assertThat(contains(stored, "GPS-DU-JOUEUR")).isFalse();
    }

    @Test
    void webpIsAccepted() throws Exception {
        MediaFile media = upload(file("pixel.webp", "image/webp",
                Base64.getDecoder().decode(TINY_WEBP)));

        assertThat(media.getWidth()).isEqualTo(1);
        assertThat(Files.exists(onDisk(media.getStorageKey()))).isTrue();
    }

    @Test
    void theAnnouncedTypeIsNeverTrusted() {
        User admin = data.admin();
        MockMultipartFile disguised = file("image.png", "image/png",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile svg = file("logo.svg", "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8));

        assertApiError(ErrorCode.MEDIA_TYPE_NOT_ALLOWED,
                () -> mediaService.upload(admin.getId(), disguised, "raf"));
        assertApiError(ErrorCode.MEDIA_TYPE_NOT_ALLOWED,
                () -> mediaService.upload(admin.getId(), svg, "raf"));
    }

    @Test
    void hugeDimensionsAreRefusedBeforeDecoding() throws Exception {
        User admin = data.admin();
        // Quelques Ko sur le disque, mais 12 000 px de large.
        MockMultipartFile banner = file("bandeau.png", "image/png", image(12_000, 10, false));

        assertApiError(ErrorCode.MEDIA_IMAGE_TOO_LARGE,
                () -> mediaService.upload(admin.getId(), banner, "raf"));
    }

    @Test
    void theFolderNeverReachesThePath() throws Exception {
        User admin = data.admin();
        MockMultipartFile picture = file("a.png", "image/png", image(10, 10, false));

        assertApiError(ErrorCode.VALIDATION_ERROR,
                () -> mediaService.upload(admin.getId(), picture, "../../etc"));
    }

    @Test
    void deletingAnImageRemovesItsFiles() throws Exception {
        User admin = data.admin();
        MediaFile media = upload(file("a.png", "image/png", image(500, 500, false)));
        Path main = onDisk(media.getStorageKey());
        Path thumbnail = onDisk(media.getThumbnailKey());

        mediaService.delete(admin.getId(), media.getId());

        assertThat(main).doesNotExist();
        assertThat(thumbnail).doesNotExist();
        assertApiError(ErrorCode.RESOURCE_NOT_FOUND, () -> mediaService.require(media.getId()));
    }

    @Test
    void imagesArePublicAndCachedButOnlyStaffUploads() throws Exception {
        MediaFile media = upload(file("a.png", "image/png", image(20, 20, false)));

        mvc.perform(get("/media/" + media.getStorageKey()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("immutable")));

        mvc.perform(multipart("/api/media/upload")
                        .file(file("a.png", "image/png", image(20, 20, false))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void aPlayerCannotUpload() throws Exception {
        mvc.perform(multipart("/api/media/upload")
                        .file(file("a.png", "image/png", image(20, 20, false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void theListIsNewestFirstFiltersByFolderAndCountsUsages() throws Exception {
        User admin = data.admin();
        String folder = "liste-" + System.nanoTime() % 1_000_000;
        MediaFile unused = mediaService.upload(admin.getId(),
                file("a.png", "image/png", image(20, 20, false)), folder);
        MediaFile used = mediaService.upload(admin.getId(),
                file("b.png", "image/png", image(20, 20, false)), folder);
        // Le back-office relit l'identifiant pour garder l'image en modifiant la categorie.
        assertThat(categoryService.create(admin.getId(), "Avec image " + folder, null, null, used.getId())
                .mediaId()).isEqualTo(used.getId());

        List<MediaResponse> listed = mediaService.list(folder, PageRequest.of(0, 10)).getContent();

        assertThat(listed).extracting(MediaResponse::id).containsExactly(used.getId(), unused.getId());
        assertThat(listed).extracting(MediaResponse::usages).containsExactly(1L, 0L);
        assertThat(mediaService.view(used.getId()).usages()).isEqualTo(1);
        assertThat(mediaService.folders()).contains(folder);
    }

    // --- Outils -------------------------------------------------------------------

    private MediaFile upload(MockMultipartFile file) {
        return mediaService.upload(data.admin().getId(), file, "raf");
    }

    private Path onDisk(String key) {
        return storage.root().resolve(key);
    }

    private static MockMultipartFile file(String name, String type, byte[] content) {
        return new MockMultipartFile("file", name, type, content);
    }

    private static byte[] image(int width, int height, boolean transparent) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(transparent ? new Color(255, 120, 0, 128) : Color.ORANGE);
        graphics.fillRect(0, 0, width / 2, height);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    /** JPEG avec un segment APP1 "Exif" glisse juste apres l'en-tete, comme un telephone. */
    private static byte[] withExif(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", jpeg);
        byte[] plain = jpeg.toByteArray();

        byte[] payload = "Exif\0\0GPS-DU-JOUEUR".getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);
        out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        out.write(payload);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }
}
