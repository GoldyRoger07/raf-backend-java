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
 *
 * <p>Recharge en ligne MonCash / NatCash : active par defaut, mais proposee
 * seulement si la passerelle est configuree (OnlineTopUpService). La couper
 * masque l'option dans l'app et refuse toute nouvelle recharge en ligne ; les
 * paiements deja lances sont toujours credites.
 *
 * <p>Retraits automatiques : actives par defaut, effectifs seulement avec une cle
 * Tranzak reelle autorisee aux payouts (AutoPayoutService). Les couper fait revenir
 * au versement manuel (l'agent envoie l'argent puis saisit la reference).
 */
@Service
@RequiredArgsConstructor
public class PlayerAppService {

    private final SettingsService settingsService;

    /** En modification, un champ absent (null) garde sa valeur actuelle. */
    public record PlayerAppConfig(Boolean emailReminderEnabled, Boolean leaderTransferEnabled,
            Boolean winningsConversionEnabled, Boolean onlineTopUpEnabled, Boolean autoPayoutEnabled) {
    }

    @Transactional(readOnly = true)
    public PlayerAppConfig get() {
        return new PlayerAppConfig(flag(SettingKeys.APP_EMAIL_REMINDER_ENABLED),
                flag(SettingKeys.APP_LEADER_TRANSFER_ENABLED), flag(SettingKeys.APP_WINNINGS_CONVERSION_ENABLED),
                flag(SettingKeys.APP_ONLINE_TOPUP_ENABLED), flag(SettingKeys.APP_AUTO_PAYOUT_ENABLED));
    }

    @Transactional(readOnly = true)
    public boolean winningsConversionEnabled() {
        return flag(SettingKeys.APP_WINNINGS_CONVERSION_ENABLED);
    }

    @Transactional(readOnly = true)
    public boolean onlineTopUpEnabled() {
        return flag(SettingKeys.APP_ONLINE_TOPUP_ENABLED);
    }

    @Transactional(readOnly = true)
    public boolean autoPayoutEnabled() {
        return flag(SettingKeys.APP_AUTO_PAYOUT_ENABLED);
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
        change(actorId, SettingKeys.APP_ONLINE_TOPUP_ENABLED, current.onlineTopUpEnabled(),
                request.onlineTopUpEnabled());
        change(actorId, SettingKeys.APP_AUTO_PAYOUT_ENABLED, current.autoPayoutEnabled(),
                request.autoPayoutEnabled());
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
