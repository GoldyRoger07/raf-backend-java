package com.raikiservice.raf.wallet.web.dto;

import com.raikiservice.raf.wallet.domain.Wallet;

/** La recharge creee par la conversion, et les deux soldes apres coup. */
public record WinningsConversionResponse(
        TopUpResponse topUp,
        long creditBalance,
        long winningsBalanceHtg) {

    public static WinningsConversionResponse from(TopUpResponse topUp, Wallet wallet) {
        return new WinningsConversionResponse(topUp, wallet.getCreditBalance(), wallet.getWinningsBalance());
    }
}
