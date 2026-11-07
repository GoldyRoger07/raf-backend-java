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
 *
 * <p>Recharge des joueurs par leur leader : active par defaut. La couper masque le
 * bouton dans l'app et refuse toute nouvelle recharge (LeaderTransferService).
 *
 * <p>Conversion des gains en credits : active par defaut. La couper masque le bouton
 * de l'ecran Recharger et refuse toute nouvelle conversion (TopUpService).
 */
@Service
@RequiredArgsConstructor
public class PlayerAppService {

    private final SettingsService settingsService;

    /** En modification, un champ absent (null) garde sa valeur actuelle. */
    public record PlayerAppConfig(Boolean emailReminderEnabled, Boolean leaderTransferEnabled,
            Boolean winningsConversionEnabled) {
    }

    @Transactional(readOnly = true)
    public PlayerAppConfig get() {
        return new PlayerAppConfig(flag(SettingKeys.APP_EMAIL_REMINDER_ENABLED),
                flag(SettingKeys.APP_LEADER_TRANSFER_ENABLED), flag(SettingKeys.APP_WINNINGS_CONVERSION_ENABLED));
    }

    @Transactional(readOnly = true)
    public boolean winningsConversionEnabled() {
        return flag(SettingKeys.APP_WINNINGS_CONVERSION_ENABLED);
    }

    @Transactional(readOnly = true)
    public boolean leaderTransferEnabled() {
        return flag(SettingKeys.APP_LEADER_TRANSFER_ENABLED);
    }

    /** Modification tracee au journal d'audit (SETTING_UPDATED). */
    @Transactional
    public PlayerAppConfig update(Long actorId, PlayerAppConfig request) {
        PlayerAppConfig current = get();
        change(actorId, SettingKeys.APP_EMAIL_REMINDER_ENABLED, current.emailReminderEnabled(),
                request.emailReminderEnabled());
        change(actorId, SettingKeys.APP_LEADER_TRANSFER_ENABLED, current.leaderTransferEnabled(),
                request.leaderTransferEnabled());
        change(actorId, SettingKeys.APP_WINNINGS_CONVERSION_ENABLED, current.winningsConversionEnabled(),
                request.winningsConversionEnabled());
        return get();
    }

    private void change(Long actorId, String key, boolean current, Boolean requested) {
        if (requested != null && requested != current) {
            settingsService.update(actorId, key, String.valueOf(requested));
        }
    }

    private boolean flag(String key) {
        return Boolean.parseBoolean(settingsService.getString(key, "true"));
    }
}
