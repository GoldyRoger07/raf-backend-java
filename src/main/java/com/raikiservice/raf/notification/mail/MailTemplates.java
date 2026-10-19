package com.raikiservice.raf.notification.mail;

/**
 * Gabarits des emails transactionnels du lot 2.
 *
 * <p>Volontairement minimalistes et en HTML inline : les clients mail ignorent
 * largement les feuilles de style externes.
 */
public final class MailTemplates {

    private MailTemplates() {
    }

    public static String verifyEmail(String firstName, String link) {
        return wrap("Bienvenue sur RAF, " + escape(firstName) + " !",
                """
                <p>Il ne reste qu'une etape : confirmez votre adresse email pour activer
                votre compte et pouvoir participer aux quiz.</p>
                """,
                "Confirmer mon adresse", link,
                "Ce lien expire dans 24 heures. Si vous n'avez pas cree de compte sur RAF, "
                        + "ignorez simplement ce message.");
    }

    public static String resetPassword(String firstName, String link) {
        return wrap("Reinitialisation de votre mot de passe",
                """
                <p>Bonjour %s,</p>
                <p>Vous avez demande a reinitialiser votre mot de passe RAF.</p>
                """.formatted(escape(firstName)),
                "Choisir un nouveau mot de passe", link,
                "Ce lien expire dans 1 heure. Si vous n'etes pas a l'origine de cette demande, "
                        + "ignorez ce message : votre mot de passe reste inchange.");
    }

    public static String passwordChanged(String firstName) {
        return wrap("Votre mot de passe a ete modifie",
                """
                <p>Bonjour %s,</p>
                <p>Le mot de passe de votre compte RAF vient d'etre modifie et toutes vos
                sessions ont ete fermees.</p>
                """.formatted(escape(firstName)),
                null, null,
                "Si vous n'etes pas a l'origine de ce changement, contactez-nous immediatement.");
    }

    /** Email d'une notification : titre, message et lien vers l'ecran concerne. */
    public static String notification(String firstName, String title, String body,
            String buttonLabel, String buttonUrl) {
        return wrap(escape(title),
                """
                <p>Bonjour %s,</p>
                <p>%s</p>
                """.formatted(escape(firstName), escape(body)),
                buttonLabel, buttonUrl,
                "Vous pouvez choisir les emails que vous recevez dans les preferences de "
                        + "notification de votre compte RAF.");
    }

    private static String wrap(String title, String body, String buttonLabel, String buttonUrl,
            String footer) {
        String button = buttonLabel == null ? ""
                : """
                <p style="margin:32px 0;">
                  <a href="%s" style="background:#0f766e;color:#ffffff;padding:12px 24px;
                     border-radius:6px;text-decoration:none;display:inline-block;">%s</a>
                </p>
                <p style="font-size:13px;color:#64748b;">
                  Si le bouton ne fonctionne pas, copiez ce lien dans votre navigateur :<br>
                  <span style="word-break:break-all;">%s</span>
                </p>
                """.formatted(buttonUrl, buttonLabel, buttonUrl);

        return """
                <div style="font-family:system-ui,-apple-system,Segoe UI,sans-serif;
                            max-width:560px;margin:0 auto;padding:24px;color:#0f172a;">
                  <h1 style="font-size:20px;margin:0 0 16px;">%s</h1>
                  %s
                  %s
                  <hr style="border:none;border-top:1px solid #e2e8f0;margin:32px 0 16px;">
                  <p style="font-size:12px;color:#64748b;margin:0;">%s</p>
                </div>
                """.formatted(title, body, button, footer);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
