package com.raikiservice.raf.common.web;

import java.util.Locale;

/** Recherche « contient » des listes du back-office, pour les requetes {@code lower(x) like :term}. */
public final class SearchTerms {

    private SearchTerms() {
    }

    /**
     * Motif LIKE en minuscules entoure de {@code %}, ou {@code null} si la recherche est vide
     * (la requete ne filtre alors pas). Les jokers saisis ({@code %}, {@code _}) sont
     * cherches tels quels.
     */
    public static String contains(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String escaped = query.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
