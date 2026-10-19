package com.raikiservice.raf.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.RefreshToken;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.RefreshTokenRepository;
import com.raikiservice.raf.common.audit.AuditAction;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.util.Hashes;
import com.raikiservice.raf.common.util.RequestContext;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle de vie des refresh tokens : emission, rotation, revocation.
 *
 * <p>Le jeton brut n'existe qu'en transit ; seule son empreinte SHA-256 est stockee.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String REASON_ROTATED = "ROTATED";
    private static final String REASON_LOGOUT = "LOGOUT";
    private static final String REASON_LOGOUT_ALL = "LOGOUT_ALL";
    private static final String REASON_REUSE = "REUSE_DETECTED";
    private static final String REASON_REVOKED_BY_USER = "REVOKED_BY_USER";
    private static final String REASON_PASSWORD_CHANGED = "PASSWORD_CHANGED";

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevoker refreshTokenRevoker;
    private final AuditService auditService;
    private final AppProperties appProperties;

    /** Jeton brut a remettre au client, accompagne de la ligne persistee. */
    public record Issued(String rawToken, RefreshToken entity) {
    }

    @Transactional
    public Issued issue(User user, HttpServletRequest request) {
        return persist(user, UUID.randomUUID().toString(), request);
    }

    /**
     * Echange un refresh token contre un nouveau, dans la meme famille.
     *
     * <p>Rejouer un jeton deja revoque signifie qu'une copie circule : la famille
     * entiere est revoquee, ce qui deconnecte l'attaquant comme la victime.
     */
    @Transactional
    public Issued rotate(String rawToken, HttpServletRequest request) {
        RefreshToken current = refreshTokenRepository.findByTokenHash(Hashes.sha256Hex(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN));

        if (current.isRevoked()) {
            // Hors transaction courante : l'exception levee juste apres annulerait
            // la revocation, et le jeton vole resterait utilisable.
            refreshTokenRevoker.revokeFamily(current.getFamilyId(), REASON_REUSE);
            auditService.record(current.getUser().getId(), AuditAction.REFRESH_TOKEN_REUSE_DETECTED,
                    "RefreshToken", String.valueOf(current.getId()));
            log.warn("Reutilisation de refresh token detectee pour l'utilisateur {} (famille {})",
                    current.getUser().getId(), current.getFamilyId());
            throw new ApiException(ErrorCode.REFRESH_TOKEN_REUSED);
        }

        if (current.isExpired()) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        }

        User user = current.getUser();
        assertUsable(user);

        Issued next = persist(user, current.getFamilyId(), request);

        current.revoke(REASON_ROTATED);
        current.setReplacedById(next.entity().getId());
        current.setLastUsedAt(Instant.now());

        return next;
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(Hashes.sha256Hex(rawToken))
                .filter(RefreshToken::isActive)
                .ifPresent(token -> {
                    token.revoke(REASON_LOGOUT);
                    auditService.record(token.getUser().getId(), AuditAction.USER_LOGGED_OUT,
                            "RefreshToken", String.valueOf(token.getId()));
                });
    }

    @Transactional
    public int revokeAll(Long userId) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, REASON_LOGOUT_ALL, Instant.now());
        auditService.record(userId, AuditAction.USER_LOGGED_OUT_ALL, "User", String.valueOf(userId));
        return revoked;
    }

    @Transactional
    public void revokeAllAfterPasswordChange(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId, REASON_PASSWORD_CHANGED, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<RefreshToken> listActiveSessions(Long userId) {
        return refreshTokenRepository.findActiveByUser(userId, Instant.now());
    }

    @Transactional
    public void revokeSession(Long userId, Long sessionId) {
        RefreshToken token = refreshTokenRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Session"));

        if (!token.getUser().getId().equals(userId)) {
            // On ne revele pas l'existence d'une session appartenant a autrui.
            throw ApiException.notFound("Session");
        }

        if (token.isActive()) {
            token.revoke(REASON_REVOKED_BY_USER);
            auditService.record(userId, AuditAction.SESSION_REVOKED, "RefreshToken",
                    String.valueOf(sessionId));
        }
    }

    private Issued persist(User user, String familyId, HttpServletRequest request) {
        String rawToken = Hashes.randomToken();

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(Hashes.sha256Hex(rawToken));
        token.setFamilyId(familyId);
        token.setExpiresAt(Instant.now().plusSeconds(appProperties.jwt().refreshTokenTtl().getSeconds()));
        token.setUserAgent(RequestContext.userAgent(request));
        token.setIpAddress(RequestContext.clientIp(request));
        token.setDeviceLabel(deviceLabel(RequestContext.userAgent(request)));
        token.setLastUsedAt(Instant.now());

        return new Issued(rawToken, refreshTokenRepository.save(token));
    }

    private void assertUsable(User user) {
        switch (user.getStatus()) {
            case BANNED -> throw new ApiException(ErrorCode.ACCOUNT_BANNED);
            case SUSPENDED -> throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED);
            default -> {
            }
        }
    }

    /** Libelle lisible affiche dans la liste des sessions de l'utilisateur. */
    private String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Appareil inconnu";
        }
        String agent = userAgent.toLowerCase();
        String os = agent.contains("android") ? "Android"
                : agent.contains("iphone") || agent.contains("ipad") ? "iOS"
                : agent.contains("windows") ? "Windows"
                : agent.contains("mac os") ? "macOS"
                : agent.contains("linux") ? "Linux"
                : "Systeme inconnu";
        String browser = agent.contains("edg/") ? "Edge"
                : agent.contains("chrome") ? "Chrome"
                : agent.contains("firefox") ? "Firefox"
                : agent.contains("safari") ? "Safari"
                : "Navigateur inconnu";
        return browser + " sur " + os;
    }
}
