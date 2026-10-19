package com.raikiservice.raf.common.util;

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

    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String agent = request.getHeader("User-Agent");
        return agent == null || agent.length() <= 255 ? agent : agent.substring(0, 255);
    }
}
