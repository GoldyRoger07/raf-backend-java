package com.raikiservice.raf.wallet.web.dto;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;

/**
 * Demande de retrait vue du back-office : avec le joueur, pour que l'agent sache a
 * qui il a affaire sans ouvrir chaque fiche.
 *
 * <p>Construite dans la transaction : le joueur est une relation paresseuse.
 */
public record AdminWithdrawalResponse(
        WithdrawalResponse withdrawal,
        Long playerId,
        String playerName,
        String playerEmail,
        String playerPhone) {

    public static AdminWithdrawalResponse from(WithdrawalRequest request) {
        User user = request.getUser();
        return new AdminWithdrawalResponse(
                WithdrawalResponse.from(request),
                user.getId(),
                user.fullName(),
                user.getEmail(),
                user.getPhone());
    }
}
