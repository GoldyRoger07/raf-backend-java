package com.raikiservice.raf.game.service;

import java.time.Instant;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.wallet.service.PayoutSpecialCaseService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accord « RAF peut me payer en plusieurs fois » : un retrait peut etre verse par
 * tranches (WithdrawalService#payInstallment) et prendre plus de temps que prevu.
 *
 * <p>Demande seulement aux joueurs de la liste des cas de retrait particuliers
 * (PayoutSpecialCaseService), comptes de test compris : sans accord, plus de nouvelle
 * partie solo. Le jeu en groupe et les retraits restent libres. Hors de la liste, rien
 * a accepter ; un accord donne reste valable si le joueur est retire puis remis.
 *
 * <p>{@link #CURRENT_VERSION} suit le texte affiche dans l'app : changer le texte et
 * augmenter la version redemande l'accord a tous.
 */
@Service
@RequiredArgsConstructor
public class PayoutTermsService {

    public static final int CURRENT_VERSION = 1;

    private final UserRepository userRepository;
    private final PayoutSpecialCaseService specialCases;
    private final AuditService auditService;

    /** Etat vu par l'app avant une partie solo. */
    public record Status(boolean required, int version) {
    }

    @Transactional(readOnly = true)
    public Status status(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur"));
        return new Status(required(user), CURRENT_VERSION);
    }

    /** Doit accepter avant sa prochaine partie solo : dans la liste, sans accord a jour. */
    public boolean required(User user) {
        return !accepted(user) && specialCases.contains(user.getId());
    }

    /** Refuse la partie solo d'un joueur qui doit d'abord accepter. */
    public void assertAccepted(User user) {
        if (required(user)) {
            throw new ApiException(ErrorCode.SOLO_PAYOUT_TERMS_REQUIRED);
        }
    }

    /**
     * Enregistre l'accord pour la version lue par le joueur. Une version perimee (texte
     * change pendant qu'il le lisait) est refusee : il doit relire le nouveau.
     */
    @Transactional
    public Status accept(Long userId, int version) {
        if (version != CURRENT_VERSION) {
            throw new ApiException(ErrorCode.PAYOUT_TERMS_OUTDATED);
        }
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("Utilisateur"));
        if (!accepted(user)) {
            user.setPayoutTermsVersion(version);
            user.setPayoutTermsAcceptedAt(Instant.now());
            auditService.record(userId, "PAYOUT_TERMS_ACCEPTED", "User", String.valueOf(userId), null,
                    "version " + version);
        }
        return new Status(false, CURRENT_VERSION);
    }

    private static boolean accepted(User user) {
        return user.getPayoutTermsVersion() != null && user.getPayoutTermsVersion() >= CURRENT_VERSION;
    }
}
