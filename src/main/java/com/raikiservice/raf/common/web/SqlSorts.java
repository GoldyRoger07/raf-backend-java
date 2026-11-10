package com.raikiservice.raf.common.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import org.springframework.data.domain.Sort;

/**
 * Liste blanche des tris d'une liste du back-office ecrite en SQL (JdbcTemplate).
 * Pendant de {@link SortableFields} pour les requetes JPQL.
 *
 * <p>Chaque nom de colonne d'API est traduit en une ou plusieurs expressions SQL ; un
 * {@code %s} y recoit le sens (ASC ou DESC), une expression sans {@code %s} garde son ordre
 * (par exemple pour laisser les valeurs vides en fin de liste). Un nom inconnu est refuse :
 * rien de ce que le client envoie n'entre tel quel dans l'ORDER BY. L'identifiant
 * departage les egalites, pour une pagination stable.
 */
public final class SqlSorts {

    private final Map<String, List<String>> fields = new LinkedHashMap<>();
    private final String idExpression;
    private final String defaultOrder;

    private SqlSorts(String idExpression, String defaultOrder) {
        this.idExpression = idExpression;
        this.defaultOrder = defaultOrder;
    }

    /**
     * @param idExpression colonne identifiant, ajoutee en dernier ({@code u.id})
     * @param defaultOrder ORDER BY sans le mot-cle, quand le client ne demande aucun tri
     */
    public static SqlSorts of(String idExpression, String defaultOrder) {
        return new SqlSorts(idExpression, defaultOrder);
    }

    public SqlSorts field(String apiName, String... expressions) {
        fields.put(apiName, List.of(expressions));
        return this;
    }

    /** Clause ORDER BY complete, precedee d'une espace. */
    public String orderBy(Sort sort) {
        if (sort.isUnsorted()) {
            return " ORDER BY " + defaultOrder;
        }
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            List<String> expressions = fields.get(order.getProperty());
            if (expressions == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Tri impossible sur \"" + order.getProperty() + "\".");
            }
            String direction = order.isAscending() ? "ASC" : "DESC";
            for (String expression : expressions) {
                parts.add(expression.contains("%s") ? String.format(expression, direction) : expression);
            }
        }
        Sort.Order first = sort.iterator().next();
        return " ORDER BY " + String.join(", ", parts) + ", " + idExpression + " "
                + (first.isAscending() ? "ASC" : "DESC");
    }
}
