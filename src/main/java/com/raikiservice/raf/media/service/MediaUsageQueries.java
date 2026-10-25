package com.raikiservice.raf.media.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ou une image est utilisee. Toutes les colonnes qui pointent vers media_files
 * sont en ON DELETE SET NULL : supprimer une image utilisee est permis, mais
 * l'ecran doit pouvoir dire ce qui perdra son visuel.
 *
 * <p>Une colonne ajoutee plus tard vers media_files doit l'etre ici aussi.
 */
@Component
@RequiredArgsConstructor
public class MediaUsageQueries {

    private static final String USAGES = """
            SELECT media_id, COUNT(*) AS usages FROM (
                SELECT media_id FROM categories WHERE media_id IN (:ids)
                UNION ALL SELECT media_id FROM questions WHERE media_id IN (:ids)
                UNION ALL SELECT media_id FROM question_choices WHERE media_id IN (:ids)
                UNION ALL SELECT banner_media_id FROM quizzes WHERE banner_media_id IN (:ids)
                UNION ALL SELECT media_id FROM quiz_topics WHERE media_id IN (:ids)
                UNION ALL SELECT avatar_media_id FROM users WHERE avatar_media_id IN (:ids)
            ) used
            GROUP BY media_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Nombre d'utilisations par image ; une image absente du resultat n'est utilisee nulle part. */
    public Map<Long, Long> countFor(Collection<Long> mediaIds) {
        Map<Long, Long> usages = new HashMap<>();
        if (mediaIds.isEmpty()) {
            return usages;
        }
        jdbc.query(USAGES, Map.of("ids", mediaIds),
                row -> {
                    usages.put(row.getLong("media_id"), row.getLong("usages"));
                });
        return usages;
    }
}
