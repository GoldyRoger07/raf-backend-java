package com.raikiservice.raf.wallet.domain;

/**
 * Les deux poches du portefeuille.
 *
 * <p>CREDIT : achete par le joueur, sert a payer les tickets, NON retirable.
 * WINNINGS : gagne lors des quiz, seul solde qui peut etre retire en gourdes.
 */
public enum BalanceType {
    CREDIT,
    WINNINGS
}
