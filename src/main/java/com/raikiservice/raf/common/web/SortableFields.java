package com.raikiservice.raf.common.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;

/**
 * Liste blanche des tris d'une liste du back-office, pour les requetes JPQL paginees.
 *
 * <p>Le client envoie des noms de colonne d'API ({@code sort=category,asc}) ; chacun est
 * traduit en une ou plusieurs expressions JPQL. Un nom inconnu est refuse : rien de ce
 * que le client envoie n'entre tel quel dans l'ORDER BY. L'identifiant departage les
 * egalites, pour qu'une ligne n'apparaisse jamais sur deux pages.
 *
 * <p>Deux formes d'expression : un simple chemin de propriete, sans alias
 * ({@code category.name}), que Spring Data prefixe lui-meme ; ou une expression calculee,
 * ecrite avec l'alias de la requete et entre parentheses ({@code (case when q.timesServed = 0 ...)}),
 * transmise telle quelle.
 */
public final class SortableFields {

    /** Une expression d'ORDER BY ; {@code fixedAscending} pour les cles qui ignorent le sens demande. */
    public record Key(String expression, boolean fixedAscending) {

        /** Expression qui suit le sens demande par le client. */
        public static Key of(String expression) {
            return new Key(expression, false);
        }

        /** Expression toujours croissante, par exemple pour garder les valeurs vides en fin de liste. */
        public static Key alwaysAscending(String expression) {
            return new Key(expression, true);
        }
    }

    private final Map<String, List<Key>> fields = new LinkedHashMap<>();
    private final String idExpression;
    private final Sort defaultSort;

    private SortableFields(String idExpression, Sort defaultSort) {
        this.idExpression = idExpression;
        this.defaultSort = defaultSort;
    }

    /**
     * @param idExpression chemin de l'identifiant, ajoute en dernier pour departager
     * @param defaultSort tri applique quand le client n'en demande aucun
     */
    public static SortableFields of(String idExpression, Sort defaultSort) {
        return new SortableFields(idExpression, defaultSort);
    }

    public SortableFields field(String apiName, Key... keys) {
        fields.put(apiName, List.of(keys));
        return this;
    }

    /** Raccourci : colonne triee sur une seule expression. */
    public SortableFields field(String apiName, String expression) {
        return field(apiName, Key.of(expression));
    }

    /** Remplace le tri du client par sa traduction validee, page et taille inchangees. */
    public Pageable apply(Pageable pageable) {
        Sort requested = pageable.getSort();
        if (requested.isUnsorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultSort);
        }
        List<Sort.Order> orders = new ArrayList<>();
        Sort.Direction direction = requested.iterator().next().getDirection();
        for (Sort.Order order : requested) {
            List<Key> keys = fields.get(order.getProperty());
            if (keys == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Tri impossible sur \"" + order.getProperty() + "\".");
            }
            for (Key key : keys) {
                Sort.Direction keyDirection = key.fixedAscending() ? Sort.Direction.ASC : order.getDirection();
                orders.add(order(keyDirection, key.expression()));
            }
        }
        orders.add(new Sort.Order(direction, idExpression));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }

    private static final java.util.regex.Pattern PROPERTY_PATH = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    /** Chemin simple : tri ordinaire, verifie par Spring Data. Expression calculee : tri non verifie. */
    private static Sort.Order order(Sort.Direction direction, String expression) {
        return PROPERTY_PATH.matcher(expression).matches()
                ? new Sort.Order(direction, expression)
                : JpaSort.unsafe(direction, expression).iterator().next();
    }
}
