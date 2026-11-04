package com.raikiservice.raf.common.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comportements de l'app joueur pilotes depuis le back-office, lus sans connexion
 * par l'app ({@code GET /api/public/app-config}).
 *
 * <p>Rappel de confirmation de l'email : la fenetre qui revient toutes les 10
 * secondes tant que l'email du joueur n'est pas confirme. Active par defaut. Le
 * couper ne leve aucune exigence : un email non confirme bloque toujours la
 * verification du telephone et les retraits.
 */
@Service
@RequiredArgsConstructor
public class PlayerAppService {

    private final SettingsService settingsService;

    public record PlayerAppConfig(boolean emailReminderEnabled) {
    }

    @Transactional(readOnly = true)
    public PlayerAppConfig get() {
        return new PlayerAppConfig(Boolean.parseBoolean(
                settingsService.getString(SettingKeys.APP_EMAIL_REMINDER_ENABLED, "true")));
    }

    /** Modification tracee au journal d'audit (SETTING_UPDATED). */
    @Transactional
    public PlayerAppConfig update(Long actorId, PlayerAppConfig request) {
        if (get().emailReminderEnabled() != request.emailReminderEnabled()) {
            settingsService.update(actorId, SettingKeys.APP_EMAIL_REMINDER_ENABLED,
                    String.valueOf(request.emailReminderEnabled()));
        }
        return get();
    }
}
