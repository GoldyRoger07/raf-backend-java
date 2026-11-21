package com.raikiservice.raf.common.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Traduit les messages d'erreur en kreyol ayisyen quand le client le demande
 * ({@code Accept-Language: ht}, envoye par l'app joueur et le site public).
 *
 * <p>Les messages restent rediges en francais dans le code ; le catalogue
 * {@code i18n/ht.json} associe chaque message francais a sa traduction. Une cle
 * peut contenir des parties variables {@code {}} (montant, delai...), reprises dans
 * la traduction par {@code {0}}, {@code {1}}... Un message absent du catalogue reste
 * en francais : un oubli ne casse rien.
 */
@Component
public class MessageTranslator {

    private static final String CATALOG = "i18n/ht.json";

    private final Map<String, String> exact = new HashMap<>();
    private final List<Template> templates = new ArrayList<>();

    private record Template(Pattern pattern, String translation) {
    }

    public MessageTranslator() {
        Map<String, String> catalog = load();
        catalog.forEach((french, creole) -> {
            if (french.startsWith("_")) {
                return;
            }
            if (french.contains("{}")) {
                templates.add(new Template(compile(french), creole));
            } else {
                exact.put(french, creole);
            }
        });
    }

    /** Vrai si le client demande le kreyol en premiere langue. */
    public static boolean wantsCreole(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return false;
        }
        String first = header.split(",")[0].split(";")[0].trim().toLowerCase();
        return first.equals("ht") || first.startsWith("ht-");
    }

    /** Message dans la langue du client : traduit si possible, sinon inchange. */
    public String translate(String message, HttpServletRequest request) {
        return message != null && wantsCreole(request) ? toCreole(message) : message;
    }

    String toCreole(String message) {
        String found = exact.get(message);
        if (found != null) {
            return found;
        }
        for (Template template : templates) {
            Matcher matcher = template.pattern().matcher(message);
            if (matcher.matches()) {
                String result = template.translation();
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    String part = matcher.group(i);
                    // Une partie variable peut etre elle-meme un mot du catalogue
                    // (« Utilisateur introuvable. »).
                    result = result.replace("{" + (i - 1) + "}", exact.getOrDefault(part, part));
                }
                return result;
            }
        }
        return message;
    }

    private static Pattern compile(String french) {
        String[] pieces = french.split("\\{}", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) {
                regex.append("(.+?)");
            }
            regex.append(Pattern.quote(pieces[i]));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static Map<String, String> load() {
        try (InputStream in = new ClassPathResource(CATALOG).getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Catalogue de traduction illisible : " + CATALOG, e);
        }
    }
}
