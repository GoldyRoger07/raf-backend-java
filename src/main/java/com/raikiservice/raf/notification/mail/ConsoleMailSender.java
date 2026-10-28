package com.raikiservice.raf.notification.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Aucun email ne part : le message est affiche dans la console du serveur, en
 * texte lisible, avec ses liens extraits pour pouvoir cliquer directement sur le
 * lien de verification ou de reinitialisation pendant les tests.
 *
 * <p>Mode par defaut tant que l'envoi reel n'est pas en place
 * ({@code app.mail.mode=console}). Le profil prod bascule sur SMTP.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "console", matchIfMissing = true)
public class ConsoleMailSender implements MailSender {

    private static final Pattern LINK = Pattern.compile("href=\"([^\"]+)\"");

    @Override
    public void send(String to, String subject, String htmlBody) {
        print(to, subject, htmlBody);
    }

    static void print(String to, String subject, String htmlBody) {
        StringBuilder out = new StringBuilder("\n")
                .append("==================== EMAIL (non envoye) ====================\n")
                .append("A       : ").append(to).append('\n')
                .append("Sujet   : ").append(subject).append('\n')
                .append("------------------------------------------------------------\n")
                .append(toText(htmlBody)).append('\n');

        List<String> links = links(htmlBody);
        if (!links.isEmpty()) {
            out.append("------------------------------------------------------------\n");
            links.forEach(link -> out.append("Lien    : ").append(link).append('\n'));
        }
        out.append("============================================================");

        log.info(out.toString());
    }

    private static List<String> links(String html) {
        List<String> links = new ArrayList<>();
        Matcher matcher = LINK.matcher(html);
        while (matcher.find()) {
            links.add(unescape(matcher.group(1)));
        }
        return links;
    }

    /** Rendu texte sommaire : suffisant pour lire un email transactionnel. */
    private static String toText(String html) {
        String text = html
                .replaceAll("(?is)<(style|script)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|tr|li)>", "\n")
                .replaceAll("<[^>]+>", "");
        return unescape(text)
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String unescape(String value) {
        return value
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }
}
