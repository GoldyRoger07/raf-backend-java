package com.raikiservice.raf.wallet.domain;

/**
 * Les deux poches du portefeuille.
 *
 * <p>CREDIT : en credits. Achete par le joueur, sert a payer les tickets et les
 * parties solo, NON retirable.
 * WINNINGS : en gourdes (HTG). Gagne lors des quiz, seul solde qui peut etre
 * retire. Tenu en HTG parce que c'est une dette reelle envers le joueur : la
 * valeur du credit peut changer sans la reevaluer.
 */
public enum BalanceType {
    CREDIT,
    WINNINGS
}
