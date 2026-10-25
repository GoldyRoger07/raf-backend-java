package com.raikiservice.raf.media.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import org.springframework.stereotype.Component;

/**
 * Prepare une image avant stockage : ce que faisait ImageKit.
 *
 * <ul>
 *   <li>le format est celui que le decodeur reconnait dans le contenu, jamais
 *       le type annonce par le navigateur ;</li>
 *   <li>les dimensions sont lues avant le decodage : une image minuscule sur le
 *       disque peut annoncer 50 000 x 50 000 pixels et epuiser la memoire ;</li>
 *   <li>l'image est reencodee : les metadonnees (EXIF, dont la position GPS
 *       d'une photo prise au telephone) et tout contenu cache disparaissent ;</li>
 *   <li>elle est ramenee a {@value #MAX_SIDE} px de cote au plus, avec une
 *       miniature de {@value #THUMBNAIL_SIDE} px.</li>
 * </ul>
 *
 * <p>Sortie en PNG si l'image a de la transparence, en JPEG sinon. Un GIF anime
 * ne garde que sa premiere image. Le SVG n'est pas accepte : c'est un document
 * qui peut contenir du script, servi depuis le domaine de RAF.
 */
@Component
public class ImageProcessor {

    static final int MAX_SIDE = 1600;
    static final int THUMBNAIL_SIDE = 400;

    /** Au-dela, le decodage seul occuperait des centaines de Mo. */
    private static final int MAX_SOURCE_SIDE = 10_000;
    private static final long MAX_SOURCE_PIXELS = 40_000_000L;

    private static final float JPEG_QUALITY = 0.85f;

    private static final Set<String> ACCEPTED_FORMATS = Set.of("jpeg", "png", "gif", "webp");

    public record ProcessedImage(byte[] content, byte[] thumbnail, String extension,
            String mimeType, int width, int height) {
    }

    public ProcessedImage process(byte[] source) {
        BufferedImage image = decode(source);

        boolean alpha = image.getColorModel().hasAlpha();
        BufferedImage main = fit(image, MAX_SIDE, alpha);
        BufferedImage thumbnail = fit(image, THUMBNAIL_SIDE, alpha);

        try {
            return alpha
                    ? new ProcessedImage(png(main), png(thumbnail), "png", "image/png",
                            main.getWidth(), main.getHeight())
                    : new ProcessedImage(jpeg(main), jpeg(thumbnail), "jpg", "image/jpeg",
                            main.getWidth(), main.getHeight());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MEDIA_UPLOAD_FAILED);
        }
    }

    private static BufferedImage decode(byte[] source) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw notAnImage();
            }
            ImageReader reader = readers.next();
            try {
                if (!ACCEPTED_FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw notAnImage();
                }
                reader.setInput(input, true, true);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE
                        || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw new ApiException(ErrorCode.MEDIA_IMAGE_TOO_LARGE,
                            "Dimensions maximales : " + MAX_SOURCE_SIDE + " px de cote.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            // Fichier tronque, corrompu ou deguise : le decodeur n'en tire rien.
            throw notAnImage();
        }
    }

    /**
     * Reduit l'image pour que son plus grand cote ne depasse pas {@code maxSide},
     * sans jamais l'agrandir. Toujours redessinee, meme a taille egale : c'est ce
     * qui la detache de son fichier d'origine.
     */
    private static BufferedImage fit(BufferedImage source, int maxSide, boolean alpha) {
        double scale = Math.min(1.0,
                (double) maxSide / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage target = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            if (!alpha) {
                // Le JPEG n'a pas de transparence : fond blanc plutot que noir.
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static ApiException notAnImage() {
        return new ApiException(ErrorCode.MEDIA_TYPE_NOT_ALLOWED,
                "Le fichier n'est pas une image lisible (JPEG, PNG, WebP ou GIF).");
    }
}
