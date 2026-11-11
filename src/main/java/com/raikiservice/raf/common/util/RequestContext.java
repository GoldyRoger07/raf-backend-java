package com.raikiservice.raf.common.util;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/** Extraction de l'origine d'une requete, pour l'audit et la detection d'abus. */
public final class RequestContext {

    private RequestContext() {
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** En-tete porte par l'app : identifiant stable de l'appareil. */
    public static final String DEVICE_ID_HEADER = "X-Device-Id";

    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    /**
     * Identifiant d'appareil, ou null s'il est absent ou mal forme. Declare par le
     * client, donc falsifiable : il arrete les comptes multiples ordinaires sur un
     * meme telephone, pas un fraudeur qui forge ses requetes.
     */
    public static String deviceId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String id = request.getHeader(DEVICE_ID_HEADER);
        if (id == null) {
            return null;
        }
        id = id.trim();
        return DEVICE_ID.matcher(id).matches() ? id : null;
    }

    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String agent = request.getHeader("User-Agent");
        return agent == null || agent.length() <= 255 ? agent : agent.substring(0, 255);
    }
}
