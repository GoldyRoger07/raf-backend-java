package com.raikiservice.raf.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Catalogue des erreurs metier. Le code est stable et destine au frontend,
 * le message est destine a l'utilisateur final.
 */
@Getter
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Les donnees envoyees sont invalides."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "La requete est mal formee."),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentification requise."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email ou mot de passe incorrect."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Vous n'avez pas les droits necessaires."),

    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "Votre compte est suspendu."),
    ACCOUNT_BANNED(HttpStatus.FORBIDDEN, "Votre compte a ete banni."),

    EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "Cette adresse email est deja utilisee."),
    PHONE_ALREADY_USED(HttpStatus.CONFLICT, "Ce numero de telephone est deja utilise."),

    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Le jeton est invalide."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Le jeton a expire."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED,
            "Ce jeton a deja ete utilise. Toutes vos sessions ont ete fermees par securite."),

    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Votre adresse email doit d'abord etre verifiee."),
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "Cette adresse email est deja verifiee."),
    PHONE_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Votre numero de telephone doit d'abord etre verifie."),
    PHONE_ALREADY_VERIFIED(HttpStatus.CONFLICT, "Ce numero de telephone est deja verifie."),

    OTP_INVALID(HttpStatus.BAD_REQUEST, "Code invalide."),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST, "Ce code a expire, demandez-en un nouveau."),
    OTP_TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "Trop de tentatives. Demandez un nouveau code."),
    OTP_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "Patientez avant de demander un nouveau code."),

    CURRENT_PASSWORD_INVALID(HttpStatus.BAD_REQUEST, "Le mot de passe actuel est incorrect."),

    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS,
            "Trop de tentatives. Reessayez dans quelques minutes."),

    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Solde insuffisant."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "Le montant est invalide."),
    AMOUNT_NOT_CONVERTIBLE(HttpStatus.BAD_REQUEST,
            "Le montant en gourdes doit etre un multiple de la valeur du credit."),
    WITHDRAWAL_BELOW_MINIMUM(HttpStatus.UNPROCESSABLE_CONTENT,
            "Le montant demande est inferieur au minimum autorise."),
    WITHDRAWAL_DAILY_LIMIT(HttpStatus.UNPROCESSABLE_CONTENT,
            "Vous avez atteint votre plafond de retrait journalier."),
    WITHDRAWAL_PENDING_EXISTS(HttpStatus.CONFLICT,
            "Une demande de retrait est deja en cours de traitement."),
    WITHDRAWAL_NOT_PENDING(HttpStatus.CONFLICT,
            "Cette demande a deja ete traitee."),
    WITHDRAWAL_NOT_APPROVED(HttpStatus.CONFLICT,
            "Cette demande doit d'abord etre approuvee."),
    TOPUP_ALREADY_PROCESSED(HttpStatus.CONFLICT, "Cette recharge a deja ete traitee."),

    MEDIA_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE,
            "L'hebergement des images n'est pas configure sur ce serveur."),
    MEDIA_UPLOAD_FAILED(HttpStatus.BAD_GATEWAY, "Le televersement de l'image a echoue."),
    MEDIA_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Ce type de fichier n'est pas accepte."),
    MEDIA_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Le fichier depasse la taille autorisee."),

    CATEGORY_HAS_QUESTIONS(HttpStatus.CONFLICT,
            "Cette categorie contient des questions et ne peut pas etre supprimee."),
    CATEGORY_SLUG_TAKEN(HttpStatus.CONFLICT, "Une categorie porte deja ce nom."),
    QUESTION_DUPLICATE(HttpStatus.CONFLICT, "Cette question existe deja dans cette categorie."),
    QUESTION_INVALID_CHOICES(HttpStatus.BAD_REQUEST,
            "Une question doit avoir exactement une bonne reponse."),
    QUESTION_IN_USE(HttpStatus.CONFLICT,
            "Cette question a deja ete servie dans un quiz : archivez-la au lieu de la supprimer."),
    IMPORT_FILE_INVALID(HttpStatus.BAD_REQUEST, "Le fichier fourni est illisible."),
    IMPORT_TOO_MANY_ROWS(HttpStatus.CONTENT_TOO_LARGE,
            "Le fichier contient trop de lignes pour un seul import."),

    QUIZ_INVALID_STATE(HttpStatus.CONFLICT,
            "Cette action n'est pas possible dans l'etat actuel du quiz."),
    QUIZ_TICKETS_CLOSED(HttpStatus.CONFLICT, "La vente de tickets est fermee pour ce quiz."),
    QUIZ_SOLD_OUT(HttpStatus.CONFLICT, "Tous les tickets de ce quiz ont ete vendus."),
    QUIZ_PLAYER_LIMIT(HttpStatus.CONFLICT,
            "Vous avez atteint le nombre maximum de tickets pour ce quiz."),
    QUIZ_POOL_INSUFFICIENT(HttpStatus.UNPROCESSABLE_CONTENT,
            "La banque de questions est trop petite pour ce quiz."),
    QUIZ_NO_REWARDS(HttpStatus.UNPROCESSABLE_CONTENT,
            "Aucune recompense n'est configuree pour ce quiz."),
    QUIZ_REWARDS_OVERLAP(HttpStatus.BAD_REQUEST,
            "Deux tranches de recompense se chevauchent."),
    QUIZ_NO_TOPIC(HttpStatus.UNPROCESSABLE_CONTENT,
            "Le sujet du quiz n'est pas defini."),
    QUIZ_TOPICS_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT,
            "Un quiz soumis au vote doit proposer au moins deux sujets."),
    VOTE_CLOSED(HttpStatus.CONFLICT, "Le vote n'est pas ouvert."),
    VOTE_REQUIRES_TICKET(HttpStatus.FORBIDDEN,
            "Seuls les detenteurs d'un ticket peuvent voter pour le sujet."),
    VOTE_ALREADY_CAST(HttpStatus.CONFLICT, "Vous avez deja vote pour ce quiz."),

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Ressource introuvable."),
    CONFLICT(HttpStatus.CONFLICT, "L'operation entre en conflit avec l'etat actuel."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Une erreur interne est survenue.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
