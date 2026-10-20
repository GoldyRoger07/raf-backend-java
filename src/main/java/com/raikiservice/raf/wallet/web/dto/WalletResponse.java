package com.raikiservice.raf.wallet.web.dto;

import com.raikiservice.raf.wallet.domain.Wallet;

/**
 * Soldes du joueur : le solde de jeu en credits (avec son equivalent en
 * gourdes, fourni par le backend pour que le frontend n'applique jamais le taux),
 * et le solde de gains, tenu directement en gourdes.
 */
public record WalletResponse(
        long creditBalance,
        long creditBalanceHtg,
        long winningsBalanceHtg,
        long htgRate) {

    public static WalletResponse from(Wallet wallet, long htgRate) {
        return new WalletResponse(
                wallet.getCreditBalance(),
                wallet.getCreditBalance() * htgRate,
                wallet.getWinningsBalance(),
                htgRate);
    }
}
