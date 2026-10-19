package com.raikiservice.raf.common.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecrit une trace dans sa propre transaction.
 *
 * <p>Separe d'{@link AuditService} a dessein : la capture d'erreur doit se faire
 * en dehors de la frontiere transactionnelle. Un try/catch place a l'interieur de
 * la methode annotee n'empeche pas la transaction d'etre marquee rollback-only,
 * et le commit echoue alors malgre l'exception avalee.
 */
@Component
@RequiredArgsConstructor
public class AuditWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog entry) {
        auditLogRepository.save(entry);
    }
}
