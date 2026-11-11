package com.raikiservice.raf.wallet.web.dto;

import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;

/**
 * Demande de retrait vue du back-office : avec le joueur, pour que l'agent sache a
 * qui il a affaire sans ouvrir chaque fiche.
 *
 * <p>Deux alertes de multi-compte : {@code linkedAccounts}, le nombre de comptes vus
 * sur un appareil du joueur, et {@code destinationUsedBy}, les autres comptes dont le
 * numero de reception est le telephone ou a deja recu un retrait.
 *
 * <p>Construite dans la transaction : le joueur est une relation paresseuse.
 */
public record AdminWithdrawalResponse(
        WithdrawalResponse withdrawal,
        Long playerId,
        String playerName,
        String playerEmail,
        String playerPhone,
        int linkedAccounts,
        List<OtherAccount> destinationUsedBy,
        /** Cas de retrait particulier : retrait fractionnable par tranches. */
        boolean specialCase) {

    /** Autre compte, pour un lien vers sa fiche. */
    public record OtherAccount(Long playerId, String playerName) {
    }

    public static AdminWithdrawalResponse from(WithdrawalRequest request, int linkedAccounts,
            List<OtherAccount> destinationUsedBy, boolean specialCase) {
        User user = request.getUser();
        return new AdminWithdrawalResponse(
                WithdrawalResponse.from(request),
                user.getId(),
                user.fullName(),
                user.getEmail(),
                user.getPhone(),
                linkedAccounts,
                destinationUsedBy,
                specialCase);
    }
}
