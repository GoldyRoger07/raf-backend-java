package com.raikiservice.raf.wallet.service;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.wallet.domain.Wallet;
import com.raikiservice.raf.wallet.repository.WalletRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creation du portefeuille, isolee dans son propre bean et sa propre transaction.
 *
 * <p>Bean distinct de {@link WalletService} pour deux raisons :
 *
 * <ul>
 *   <li>un appel interne ({@code this.methode()}) ne passe pas par le proxy Spring,
 *       donc {@code REQUIRES_NEW} y serait silencieusement ignore ;
 *   <li>la creation doit utiliser un contexte de persistance separe, pour ne pas
 *       laisser une copie non verrouillee du portefeuille dans celui de l'appelant.
 *       Hibernate renverrait ensuite cette copie periment depuis son cache de
 *       premier niveau au lieu de relire la ligne verrouillee.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WalletProvisioner {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Wallet createIfAbsent(Long userId) {
        return walletRepository.findByUserId(userId).orElseGet(() -> create(userId));
    }

    private Wallet create(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));

        Wallet wallet = new Wallet();
        wallet.setUser(user);
        wallet.setCreditBalance(0);
        wallet.setWinningsBalance(0);

        try {
            return walletRepository.save(wallet);
        } catch (DataIntegrityViolationException e) {
            // Course perdue contre une requete concurrente : le portefeuille
            // qu'elle vient de creer fait autorite.
            return walletRepository.findByUserId(userId)
                    .orElseThrow(() -> ApiException.notFound("Portefeuille"));
        }
    }
}
