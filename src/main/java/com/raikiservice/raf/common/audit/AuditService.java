package com.raikiservice.raf.common.audit;

import com.raikiservice.raf.common.async.AfterTransactionExecutor;
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
 * <p>L'entree est construite tout de suite (l'IP et le navigateur ne sont
 * disponibles que pendant la requete), puis ecrite <em>apres</em> la fin de la
 * transaction metier, qu'elle soit validee ou annulee, par
 * {@link AfterTransactionExecutor} : une operation annulee laisse ainsi la trace
 * de la tentative, un echec d'audit ne fait jamais echouer l'operation tracee, et
 * le thread de la requete n'a jamais besoin d'une seconde connexion (voir
 * {@link AfterTransactionExecutor} pour l'epuisement du pool que cela evite).
 *
 * <p>Consequence : l'entree apparait en base quelques millisecondes apres la fin
 * de l'operation, et non dans la meme transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditWriter auditWriter;
    private final AfterTransactionExecutor afterTransactionExecutor;

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

            afterTransactionExecutor.afterCompletion("audit " + action, () -> auditWriter.write(entry));
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
