package com.raikiservice.raf.wallet.service;

import java.util.Optional;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.Wallet;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.repository.WalletRepository;
import com.raikiservice.raf.wallet.repository.WalletTransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Point de passage unique pour toute modification de solde.
 *
 * <p>Aucun autre service n'ecrit dans wallets : les lots suivants (achat de ticket,
 * versement des gains) appellent {@link #debit} et {@link #credit}, qui garantissent
 * ensemble le verrou, l'idempotence et la trace.
 *
 * <p>Toutes les methodes d'ecriture exigent une transaction existante
 * ({@code MANDATORY}) : un mouvement d'argent n'a de sens que dans l'operation
 * metier qui le justifie, et doit etre annule avec elle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final long DEFAULT_HTG_RATE = 5L;

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletProvisioner walletProvisioner;
    private final SettingsService settingsService;

    /** Portefeuille du joueur, cree a la volee si absent. */
    public Wallet getOrCreate(Long userId) {
        return walletProvisioner.createIfAbsent(userId);
    }

    @Transactional(readOnly = true)
    public Wallet requireWallet(Long userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Portefeuille"));
    }

    @Transactional(readOnly = true)
    public Page<WalletTransaction> history(Long userId, Pageable pageable) {
        Wallet wallet = requireWallet(userId);
        return walletTransactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction credit(Long userId, BalanceType balanceType, long amount,
            WalletTransactionType type, String referenceType, String referenceId,
            String idempotencyKey, String description) {
        return apply(userId, balanceType, amount, type, referenceType, referenceId,
                idempotencyKey, description);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction debit(Long userId, BalanceType balanceType, long amount,
            WalletTransactionType type, String referenceType, String referenceId,
            String idempotencyKey, String description) {
        return apply(userId, balanceType, -amount, type, referenceType, referenceId,
                idempotencyKey, description);
    }

    /**
     * Applique un mouvement signe sous verrou pessimiste.
     *
     * <p><strong>La lecture verrouillee doit etre la toute premiere lecture du
     * portefeuille dans cette transaction.</strong> Si une lecture non verrouillee
     * l'a precedee, Hibernate retrouve l'entite dans son cache de premier niveau et
     * renvoie cette copie periment sans relire la ligne : le verrou est pris en base
     * mais le solde utilise date d'avant, et deux debits concurrents partent du meme
     * montant. C'est pourquoi la creation eventuelle du portefeuille est deleguee a
     * {@link WalletProvisioner}, qui travaille dans un contexte separe, et precedee
     * d'un simple test d'existence qui ne charge rien.
     *
     * <p>L'idempotence est verifiee apres le verrou : deux appels concurrents portant
     * la meme cle sont ainsi serialises, et le second retrouve la transaction ecrite
     * par le premier au lieu de la rejouer.
     */
    private WalletTransaction apply(Long userId, BalanceType balanceType, long signedAmount,
            WalletTransactionType type, String referenceType, String referenceId,
            String idempotencyKey, String description) {

        if (signedAmount == 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "Le montant ne peut pas etre nul.");
        }

        // Le portefeuille doit exister AVANT le verrou : voir existsByUserId.
        if (!walletRepository.existsByUserId(userId)) {
            walletProvisioner.createIfAbsent(userId);
        }

        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> ApiException.notFound("Portefeuille"));

        if (idempotencyKey != null) {
            Optional<WalletTransaction> replay =
                    walletTransactionRepository.findByIdempotencyKey(idempotencyKey);
            if (replay.isPresent()) {
                log.info("Mouvement deja applique (cle {}), rejeu ignore", idempotencyKey);
                return replay.get();
            }
        }

        long current = wallet.balanceOf(balanceType);
        long updated = current + signedAmount;

        if (updated < 0) {
            String unit = balanceType == BalanceType.WINNINGS ? " HTG" : " credit(s)";
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Solde insuffisant : " + current + unit + " disponible(s), "
                            + Math.abs(signedAmount) + unit + " demande(s).");
        }

        wallet.setBalanceOf(balanceType, updated);

        WalletTransaction transaction = new WalletTransaction();
        transaction.setWallet(wallet);
        transaction.setType(type);
        transaction.setBalanceType(balanceType);
        transaction.setAmount(signedAmount);
        transaction.setBalanceAfter(updated);
        transaction.setReferenceType(referenceType);
        transaction.setReferenceId(referenceId);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setDescription(description);

        return walletTransactionRepository.save(transaction);
    }

    // --- Conversion credits / gourdes ---------------------------------------

    public long htgRate() {
        return settingsService.getLong(SettingKeys.CREDIT_HTG_RATE, DEFAULT_HTG_RATE);
    }

    public long creditsToHtg(long credits) {
        return credits * htgRate();
    }

    /** Refuse une somme qui ne tombe pas juste : on ne cree jamais de demi-credit. */
    public long htgToCredits(long htg) {
        long rate = htgRate();
        if (htg <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT);
        }
        if (htg % rate != 0) {
            throw new ApiException(ErrorCode.AMOUNT_NOT_CONVERTIBLE,
                    "Le montant doit etre un multiple de " + rate + " HTG.");
        }
        return htg / rate;
    }
}
