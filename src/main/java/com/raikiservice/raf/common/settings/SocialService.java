package com.raikiservice.raf.common.settings;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lien vers le compte Instagram de Raiki Services, affiche dans l'app et sur le
 * site quand l'administrateur l'active.
 *
 * <p>Seul un compte instagram.com est accepte : l'adresse est rendue telle quelle
 * dans des liens publics, elle ne doit pas pouvoir pointer ailleurs.
 */
@Service
@RequiredArgsConstructor
public class SocialService {

    /** Nom d'utilisateur Instagram : lettres, chiffres, points et soulignes, 30 au plus. */
    private static final Pattern HANDLE = Pattern.compile(
            "^(?:@|(?:https?://)?(?:www\\.)?instagram\\.com/)?([A-Za-z0-9._]{1,30})/?(?:\\?.*)?$");

    private final SettingsService settingsService;

    /** Ce que voient les joueurs : l'adresse n'est donnee que si le lien est active. */
    public record PublicLinks(String instagramUrl) {
    }

    /** Ce que voit l'administrateur. */
    public record Social(boolean instagramEnabled, String instagramUrl, String instagramHandle) {
    }

    @Transactional(readOnly = true)
    public Social get() {
        String url = settingsService.getString(SettingKeys.INSTAGRAM_URL, null);
        boolean enabled = Boolean.parseBoolean(settingsService.getString(SettingKeys.INSTAGRAM_ENABLED, "false"));
        return new Social(enabled && url != null, url, handleOf(url));
    }

    @Transactional(readOnly = true)
    public PublicLinks publicLinks() {
        Social social = get();
        return new PublicLinks(social.instagramEnabled() ? social.instagramUrl() : null);
    }

    /**
     * Enregistre le lien. {@code account} accepte l'adresse du profil, le nom
     * d'utilisateur ou {@code @nom} ; il est ramene a l'adresse canonique.
     */
    @Transactional
    public Social update(Long actorId, boolean enabled, String account) {
        String url = account == null || account.isBlank() ? null : canonicalUrl(account.trim());
        if (enabled && url == null) {
            throw new ApiException(ErrorCode.SETTING_INVALID,
                    "Indiquez le compte Instagram avant d'activer le lien.");
        }

        Social current = get();
        if (!Objects.equals(current.instagramUrl(), url)) {
            settingsService.update(actorId, SettingKeys.INSTAGRAM_URL, url);
        }
        if (current.instagramEnabled() != enabled) {
            settingsService.update(actorId, SettingKeys.INSTAGRAM_ENABLED, String.valueOf(enabled));
        }
        return get();
    }

    private static String canonicalUrl(String account) {
        Matcher matcher = HANDLE.matcher(account);
        if (!matcher.matches()) {
            throw new ApiException(ErrorCode.SETTING_INVALID,
                    "Compte Instagram invalide : indiquez par exemple @raikiservices ou "
                            + "https://www.instagram.com/raikiservices/.");
        }
        return "https://www.instagram.com/" + matcher.group(1) + "/";
    }

    private static String handleOf(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = HANDLE.matcher(url);
        return matcher.matches() ? "@" + matcher.group(1) : null;
    }
}
