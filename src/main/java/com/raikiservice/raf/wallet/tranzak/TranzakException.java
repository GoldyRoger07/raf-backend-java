package com.raikiservice.raf.wallet.tranzak;

/** Appel a Tranzak refuse ou impossible. Le message est destine aux journaux. */
public class TranzakException extends RuntimeException {

    /** Code d'erreur Tranzak (amount_too_low, provider_unavailable...), ou null. */
    private final String code;
    private final int httpStatus;

    public TranzakException(String message, String code, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean notFound() {
        return httpStatus == 404;
    }
}
