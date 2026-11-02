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
 * Numeros MonCash et NatCash de l'agent RAF pour les recharges a distance.
 *
 * <p>Le joueur envoie l'argent a ce numero depuis le telephone de son compte RAF ;
 * l'agent reconnait le numero de l'expediteur et credite le compte du montant
 * recu (recharge manuelle). L'app n'affiche que les numeros actives.
 *
 * <p>Les numeros sont ramenes au format E.164 haitien (+509 et 8 chiffres) :
 * les joueurs y envoient de l'argent, une faute de frappe ne doit pas passer.
 */
@Service
@RequiredArgsConstructor
public class DepositNumbersService {

    /** +509 facultatif puis 8 chiffres, une fois retires les separateurs usuels. */
    private static final Pattern HAITI_NUMBER = Pattern.compile("^(?:\\+?509)?(\\d{8})$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-()]");

    private final SettingsService settingsService;

    /** Ce que voient les joueurs : seulement les numeros actives. */
    public record PublicNumbers(String moncashNumber, String natcashNumber) {
    }

    /** Ce que voit l'administrateur. */
    public record Numbers(boolean moncashEnabled, String moncashNumber,
            boolean natcashEnabled, String natcashNumber) {
    }

    @Transactional(readOnly = true)
    public Numbers get() {
        String moncash = settingsService.getString(SettingKeys.DEPOSIT_MONCASH_NUMBER, null);
        String natcash = settingsService.getString(SettingKeys.DEPOSIT_NATCASH_NUMBER, null);
        return new Numbers(
                moncash != null && flag(SettingKeys.DEPOSIT_MONCASH_ENABLED), moncash,
                natcash != null && flag(SettingKeys.DEPOSIT_NATCASH_ENABLED), natcash);
    }

    @Transactional(readOnly = true)
    public PublicNumbers publicNumbers() {
        Numbers numbers = get();
        return new PublicNumbers(
                numbers.moncashEnabled() ? numbers.moncashNumber() : null,
                numbers.natcashEnabled() ? numbers.natcashNumber() : null);
    }

    /** Numero vide pour le retirer ; un numero active doit etre renseigne. */
    @Transactional
    public Numbers update(Long actorId, boolean moncashEnabled, String moncashNumber,
            boolean natcashEnabled, String natcashNumber) {
        String moncash = normalize("MonCash", moncashNumber, moncashEnabled);
        String natcash = normalize("NatCash", natcashNumber, natcashEnabled);

        Numbers current = get();
        save(actorId, SettingKeys.DEPOSIT_MONCASH_NUMBER, current.moncashNumber(), moncash);
        save(actorId, SettingKeys.DEPOSIT_MONCASH_ENABLED, current.moncashEnabled(), moncashEnabled);
        save(actorId, SettingKeys.DEPOSIT_NATCASH_NUMBER, current.natcashNumber(), natcash);
        save(actorId, SettingKeys.DEPOSIT_NATCASH_ENABLED, current.natcashEnabled(), natcashEnabled);
        return get();
    }

    private void save(Long actorId, String key, Object current, Object value) {
        if (!Objects.equals(current, value)) {
            settingsService.update(actorId, key, value == null ? null : String.valueOf(value));
        }
    }

    private boolean flag(String key) {
        return Boolean.parseBoolean(settingsService.getString(key, "false"));
    }

    private static String normalize(String label, String raw, boolean enabled) {
        String compact = raw == null ? "" : SEPARATORS.matcher(raw).replaceAll("");
        if (compact.isEmpty()) {
            if (enabled) {
                throw new ApiException(ErrorCode.SETTING_INVALID,
                        "Indiquez le numero " + label + " avant de l'activer.");
            }
            return null;
        }
        Matcher matcher = HAITI_NUMBER.matcher(compact);
        if (!matcher.matches()) {
            throw new ApiException(ErrorCode.SETTING_INVALID,
                    "Numero " + label + " invalide : indiquez par exemple +509 3712 3456.");
        }
        return "+509" + matcher.group(1);
    }
}
