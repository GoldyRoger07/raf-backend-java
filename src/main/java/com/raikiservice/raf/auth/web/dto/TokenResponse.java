package com.raikiservice.raf.auth.web.dto;

/**
 * Reponse de connexion.
 *
 * <p>Pour un compte du personnel, la connexion se fait en deux etapes : la premiere
 * reponse ne porte aucun jeton, seulement {@code mfaRequired = true} et un
 * {@code mfaToken} a renvoyer avec le code recu par email (voir
 * {@code POST /api/auth/login/verify}).
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user,
        boolean mfaRequired,
        String mfaToken) {

    public static TokenResponse of(String accessToken, String refreshToken, long expiresIn,
            UserResponse user) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, user, false, null);
    }

    /** Premiere etape d'une connexion du personnel : ni jeton d'acces, ni profil. */
    public static TokenResponse mfaChallenge(String mfaToken, long expiresIn) {
        return new TokenResponse(null, null, null, expiresIn, null, true, mfaToken);
    }
}
