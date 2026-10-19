package com.raikiservice.raf.common.audit;

/** Actions tracees dans le journal d'audit. */
public final class AuditAction {

    public static final String USER_REGISTERED = "USER_REGISTERED";
    public static final String USER_LOGGED_IN = "USER_LOGGED_IN";
    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";
    public static final String USER_LOGGED_OUT = "USER_LOGGED_OUT";
    public static final String USER_LOGGED_OUT_ALL = "USER_LOGGED_OUT_ALL";
    public static final String EMAIL_VERIFIED = "EMAIL_VERIFIED";
    public static final String PHONE_VERIFIED = "PHONE_VERIFIED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    public static final String REFRESH_TOKEN_REUSE_DETECTED = "REFRESH_TOKEN_REUSE_DETECTED";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";

    private AuditAction() {
    }
}
