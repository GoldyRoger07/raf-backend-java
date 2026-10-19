package com.raikiservice.raf.wallet.web.dto;

import com.raikiservice.raf.wallet.domain.Wallet;

/**
 * Soldes du joueur. Les montants en gourdes sont fournis par le backend pour que
 * le frontend n'ait jamais a connaitre ni appliquer le taux de conversion.
 */
public record WalletResponse(
        long creditBalance,
        long winningsBalance,
        long creditBalanceHtg,
        long winningsBalanceHtg,
        long htgRate) {

    public static WalletResponse from(Wallet wallet, long htgRate) {
        return new WalletResponse(
                wallet.getCreditBalance(),
                wallet.getWinningsBalance(),
                wallet.getCreditBalance() * htgRate,
                wallet.getWinningsBalance() * htgRate,
                htgRate);
    }
}
