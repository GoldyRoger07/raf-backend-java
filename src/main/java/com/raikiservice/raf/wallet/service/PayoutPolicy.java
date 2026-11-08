package com.raikiservice.raf.wallet.service;

import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.wallet.domain.PaymentProvider;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Regles des retraits automatiques (payouts Tranzak), communes a la demande et a
 * l'envoi.
 *
 * <p>Frais : seuls ceux de la passerelle, retenus au joueur. Tranzak debite le
 * montant envoye plus p % ; pour que le total reste dans le montant demande, RAF
 * envoie {@code floor(montant / (1 + p))} : 1 000 HTG demandes a 3,5 % donnent
 * 966 HTG recus, Tranzak debite 999,81 HTG.
 */
@Component
@RequiredArgsConstructor
public class PayoutPolicy {

    private static final long DEFAULT_FEE_BPS = 350;
    private static final long MAX_FEE_BPS = 2_000;

    private final PlayerAppService playerAppService;
    private final SettingsService settingsService;
    private final TranzakPayoutGateway gateway;

    /** Interrupteur du back-office et cle reelle autorisee aux payouts. */
    public boolean active() {
        return playerAppService.autoPayoutEnabled() && gateway.isConfigured();
    }

    public long feeBps() {
        return Math.clamp(settingsService.getLong(SettingKeys.WITHDRAWAL_PAYOUT_FEE_BPS, DEFAULT_FEE_BPS), 0,
                MAX_FEE_BPS);
    }

    /** Montant envoye au joueur pour {@code amountHtg} retires. */
    public long netFor(long amountHtg) {
        return Math.floorDiv(amountHtg * 10_000, 10_000 + feeBps());
    }

    /** Envoi possible par la passerelle : MonCash ou NatCash, hors retrait au guichet. */
    public static boolean eligible(WithdrawalRequest request) {
        return (request.getProvider() == PaymentProvider.MONCASH || request.getProvider() == PaymentProvider.NATCASH)
                && !request.isCounterWithdrawal();
    }

    public static boolean eligible(PaymentProvider provider) {
        return provider == PaymentProvider.MONCASH || provider == PaymentProvider.NATCASH;
    }
}
