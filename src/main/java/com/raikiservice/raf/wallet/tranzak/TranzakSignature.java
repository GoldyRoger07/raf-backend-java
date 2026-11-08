package com.raikiservice.raf.wallet.tranzak;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature des webhooks Tranzak : HMAC-SHA256 du corps brut, cle = secret du
 * website (whsec_...), en hexadecimal dans l'en-tete X-Tranzak-Signature. Le
 * corps doit etre celui recu, octet pour octet : du JSON re-serialise ne
 * donnerait pas la meme signature.
 */
public final class TranzakSignature {

    private TranzakSignature() {
    }

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    /** Comparaison en temps constant : ne revele pas combien de caracteres sont justes. */
    public static boolean verify(String secret, byte[] body, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
        byte[] received = signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, received);
    }
}
