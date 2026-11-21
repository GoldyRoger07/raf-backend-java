package com.raikiservice.raf.wallet.service;

import java.util.function.Function;

import com.raikiservice.raf.auth.domain.OtpPurpose;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.service.OtpService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.notification.mail.MailTemplates;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.wallet.domain.CodeChannel;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canal des codes de retrait : SMS (par defaut) ou email, en secours si les SMS sont
 * indisponibles (abonnement Twilio suspendu par exemple).
 *
 * <p>Concerne la confirmation d'un retrait, le code de remise au guichet et le retrait
 * saisi au guichet. La verification du telephone reste par SMS.
 *
 * <p>Interrupteur manuel, reserve au SUPER_ADMIN, coupe par defaut. Active :
 * <ul>
 *   <li>les codes partent a l'adresse email du compte ;</li>
 *   <li>un email confirme suffit pour retirer, le telephone verifie n'est plus exige
 *       (sans SMS, un nouveau joueur ne pourrait plus le faire verifier) ;</li>
 *   <li>chaque retrait confirme par email le garde (WithdrawalRequest#otpChannel),
 *       affiche dans le back-office pour une verification attentive.</li>
 * </ul>
 * Chaque bascule est tracee (SETTING_UPDATED) et signalee par email aux administrateurs.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalCodeService {

    private final SettingsService settingsService;
    private final OtpService otpService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public record Config(boolean byEmail) {
    }

    @Transactional(readOnly = true)
    public boolean byEmail() {
        return Boolean.parseBoolean(settingsService.getString(SettingKeys.APP_WITHDRAWAL_CODES_BY_EMAIL, "false"));
    }

    @Transactional(readOnly = true)
    public Config config() {
        return new Config(byEmail());
    }

    @Transactional
    public Config update(Long actorId, boolean byEmail) {
        if (byEmail != byEmail()) {
            settingsService.update(actorId, SettingKeys.APP_WITHDRAWAL_CODES_BY_EMAIL, String.valueOf(byEmail));
            User actor = userRepository.findById(actorId).orElseThrow(() -> ApiException.notFound("Utilisateur"));
            notificationService.notifyAdmins(NotificationMessages.withdrawalCodesChannelChanged(actor, byEmail));
        }
        return config();
    }

    /**
     * Le joueur peut-il recevoir un code de retrait ? SMS : telephone verifie.
     * Email : adresse email confirmee.
     */
    @Transactional(readOnly = true)
    public void assertCanReceiveCode(User user, boolean byAgent) {
        if (byEmail()) {
            if (!user.isEmailVerified()) {
                throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED, byAgent
                        ? "Le joueur doit d'abord confirmer son adresse email."
                        : "Confirmez votre adresse email avant de demander un retrait.");
            }
        } else if (!user.isPhoneVerified()) {
            throw new ApiException(ErrorCode.PHONE_NOT_VERIFIED, byAgent
                    ? "Le joueur doit d'abord verifier son numero de telephone."
                    : "Verifiez votre numero de telephone avant de demander un retrait.");
        }
    }

    /**
     * Envoie un code de retrait par le canal en vigueur.
     *
     * @param smsText message SMS a partir du code (la duree de validite est ajoutee)
     * @param action  ce que le code autorise, repris dans l'email
     * @return le canal utilise
     */
    @Transactional
    public CodeChannel send(User user, OtpPurpose purpose, Function<String, String> smsText, String action) {
        if (byEmail()) {
            otpService.sendByEmail(user, purpose, "Votre code de retrait RAF",
                    (code, minutes) -> MailTemplates.withdrawalCode(user.getFirstName(), action, code, minutes));
            return CodeChannel.EMAIL;
        }
        otpService.send(user, purpose, user.getPhone(), smsText);
        return CodeChannel.SMS;
    }
}
