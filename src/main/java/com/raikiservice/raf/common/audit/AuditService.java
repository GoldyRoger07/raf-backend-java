package com.raikiservice.raf.common.audit;

import com.raikiservice.raf.common.util.RequestContext;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Ecriture du journal d'audit.
 *
 * <p>Deliberement non transactionnel : l'ecriture reelle est deleguee a
 * {@link AuditWriter}, qui ouvre sa propre transaction. Une operation metier annulee
 * laisse ainsi la trace de la tentative, et un echec d'audit ne fait jamais echouer
 * l'operation tracee.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditWriter auditWriter;

    public void record(Long actorUserId, String action, String entityType, String entityId) {
        record(actorUserId, action, entityType, entityId, null, null);
    }

    public void record(Long actorUserId, String action, String entityType, String entityId,
            String beforeState, String afterState) {
        try {
            AuditLog entry = new AuditLog();
            entry.setActorUserId(actorUserId);
            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId);
            entry.setBeforeState(beforeState);
            entry.setAfterState(afterState);

            HttpServletRequest request = currentRequest();
            entry.setIpAddress(RequestContext.clientIp(request));
            entry.setUserAgent(RequestContext.userAgent(request));

            auditWriter.write(entry);
        } catch (RuntimeException e) {
            log.error("Echec d'ecriture du journal d'audit pour l'action {}", action, e);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
