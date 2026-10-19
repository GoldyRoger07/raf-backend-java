package com.raikiservice.raf.media.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client ImageKit.
 *
 * <p>Deux voies de televersement coexistent, volontairement :
 *
 * <ul>
 *   <li><b>Cote client</b> ({@link #authenticationParameters()}) : le frontend
 *       envoie le fichier directement a ImageKit avec une signature courte emise
 *       ici. Le binaire ne traverse pas le backend, ce qui compte sur des
 *       connexions lentes.
 *   <li><b>Cote serveur</b> ({@link #upload}) : pour les flux administratifs
 *       (imports, scripts) ou aucun navigateur n'intervient.
 * </ul>
 */
@Slf4j
@Component
public class ImageKitClient {

    private static final String UPLOAD_URL = "https://upload.imagekit.io/api/v1/files/upload";
    private static final String FILES_URL = "https://api.imagekit.io/v1/files";
    private static final long SIGNATURE_TTL_SECONDS = 1800;

    private final RestClient restClient = RestClient.create();
    private final AppProperties.ImageKit config;

    public ImageKitClient(AppProperties properties) {
        this.config = properties.imagekit();
    }

    public boolean isConfigured() {
        return config.isConfigured();
    }

    /** Parametres a remettre au frontend pour un televersement direct. */
    public Map<String, Object> authenticationParameters() {
        requireConfigured();

        String token = UUID.randomUUID().toString();
        long expire = Instant.now().getEpochSecond() + SIGNATURE_TTL_SECONDS;

        return Map.of(
                "token", token,
                "expire", expire,
                "signature", sign(token + expire),
                "publicKey", config.publicKey(),
                "urlEndpoint", config.urlEndpoint() == null ? "" : config.urlEndpoint());
    }

    /**
     * ImageKit attend un HMAC-SHA1 de (token + expire) avec la cle privee.
     * La cle privee ne sort jamais du serveur ; seule la signature circule.
     */
    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(
                    config.privateKey().getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Signature ImageKit impossible", e);
        }
    }

    public UploadResult upload(byte[] content, String fileName, String folder) {
        requireConfigured();

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", asResource(content, fileName));
        form.add("fileName", fileName);
        form.add("useUniqueFileName", "true");
        if (folder != null && !folder.isBlank()) {
            form.add("folder", folder);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.post()
                    .uri(UPLOAD_URL)
                    .header("Authorization", basicAuth())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            if (body == null || body.get("fileId") == null) {
                throw new ApiException(ErrorCode.MEDIA_UPLOAD_FAILED);
            }

            return new UploadResult(
                    String.valueOf(body.get("fileId")),
                    String.valueOf(body.get("url")),
                    body.get("thumbnailUrl") == null ? null : String.valueOf(body.get("thumbnailUrl")),
                    body.get("filePath") == null ? null : String.valueOf(body.get("filePath")));
        } catch (RestClientException e) {
            log.error("Televersement ImageKit echoue pour {}", fileName, e);
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_FAILED);
        }
    }

    /**
     * Supprime le fichier chez ImageKit.
     *
     * <p>Renvoie false au lieu de lever : la ligne en base doit pouvoir etre
     * supprimee meme si l'appel distant echoue, quitte a laisser un orphelin a
     * nettoyer. Bloquer la suppression locale sur une panne distante serait pire.
     */
    public boolean delete(String imagekitFileId) {
        if (!isConfigured()) {
            return false;
        }
        try {
            restClient.delete()
                    .uri(FILES_URL + "/" + imagekitFileId)
                    .header("Authorization", basicAuth())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.error("Suppression ImageKit echouee pour le fichier {}", imagekitFileId, e);
            return false;
        }
    }

    private Resource asResource(byte[] content, String fileName) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private String basicAuth() {
        String credentials = config.privateKey() + ":";
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ApiException(ErrorCode.MEDIA_NOT_CONFIGURED);
        }
    }

    public record UploadResult(String fileId, String url, String thumbnailUrl, String filePath) {
    }
}
