package com.raikiservice.raf.admin.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.raikiservice.raf.admin.web.dto.AuditEntryResponse;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditLog;
import com.raikiservice.raf.common.audit.AuditLogRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consultation du journal d'audit (lecture seule : le journal n'est jamais modifie). */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public record Filters(List<String> actions, List<String> entityTypes) {
    }

    @Transactional(readOnly = true)
    public Page<AuditEntryResponse> search(String action, String entityType, String entityId,
            Long actorId, Pageable pageable) {
        Page<AuditLog> page = auditLogRepository.search(blankToNull(action), blankToNull(entityType),
                blankToNull(entityId), actorId, pageable);

        // Noms des auteurs en une requete pour toute la page, pas une par ligne.
        Set<Long> actorIds = page.getContent().stream()
                .map(AuditLog::getActorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, String> names = userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, User::fullName, (a, b) -> a));

        return page.map(entry -> new AuditEntryResponse(
                entry.getId(),
                entry.getCreatedAt(),
                entry.getActorUserId(),
                entry.getActorUserId() == null ? null : names.getOrDefault(entry.getActorUserId(), "Compte supprime"),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getBeforeState(),
                entry.getAfterState(),
                entry.getIpAddress()));
    }

    @Transactional(readOnly = true)
    public Filters filters() {
        return new Filters(auditLogRepository.findDistinctActions(),
                auditLogRepository.findDistinctEntityTypes());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
