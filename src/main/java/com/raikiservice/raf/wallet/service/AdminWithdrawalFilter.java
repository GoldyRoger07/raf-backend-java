package com.raikiservice.raf.wallet.service;

import java.time.LocalDate;

import com.raikiservice.raf.wallet.domain.PaymentProvider;

/**
 * Criteres facultatifs de la liste des retraits du back-office, en plus du statut.
 *
 * @param query nom, email ou telephone du joueur, numero de destination, ou numero de demande ({@code 42} ou {@code #42})
 * @param counter {@code true} : retraits saisis au guichet seulement, {@code false} : demandes des joueurs seulement
 * @param from demandes faites ce jour-la ou apres (jour d'Haiti)
 * @param to demandes faites ce jour-la ou avant (jour d'Haiti)
 */
public record AdminWithdrawalFilter(String query, PaymentProvider provider, Boolean counter,
        LocalDate from, LocalDate to) {

    public static final AdminWithdrawalFilter NONE = new AdminWithdrawalFilter(null, null, null, null, null);
}
