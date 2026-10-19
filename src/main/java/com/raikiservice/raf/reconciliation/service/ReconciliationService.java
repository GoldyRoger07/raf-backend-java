package com.raikiservice.raf.reconciliation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.reconciliation.domain.ReconciliationFinding;
import com.raikiservice.raf.reconciliation.domain.ReconciliationRun;
import com.raikiservice.raf.reconciliation.domain.ReconciliationStatus;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.repository.ReconciliationFindingRepository;
import com.raikiservice.raf.reconciliation.repository.ReconciliationRunRepository;
import com.raikiservice.raf.reconciliation.web.dto.ReconciliationRunResponse;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rapprochement comptable : verifie que l'argent enregistre est coherent partout.
 *
 * <p>Deux familles de controles (detail dans {@link ReconciliationQueries}) :
 * <ul>
 *   <li>le journal : chaque solde egale la somme de ses transactions, et chaque
 *       solde apres mouvement egale le precedent plus le montant ;
 *   <li>les operations metier : chaque ticket, recharge, retrait et gain pointe
 *       vers le bon mouvement, et aucun mouvement n'est orphelin.
 * </ul>
 *
 * <p>Le rapprochement ne corrige rien : un ecart signale une erreur de code ou une
 * modification manuelle de la base, et merite une enquete, pas une correction
 * automatique. Il alerte (journal d'erreurs et audit) et conserve le detail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    /** Detail conserve par execution ; le compte total, lui, est toujours exact. */
    static final int MAX_STORED_FINDINGS = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationFindingRepository findingRepository;
    private final WalletService walletService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public ReconciliationRunResponse run(ReconciliationTrigger trigger, Long actorId) {
        ReconciliationRun run = new ReconciliationRun();
        run.setTrigger(trigger);
        run.setTriggeredBy(actorId);
        run.setStartedAt(Instant.now());

        List<ReconciliationFinding> findings = new ArrayList<>();
        int total = 0;
        try {
            for (ReconciliationQueries.Query query : ReconciliationQueries.all()) {
                List<ReconciliationFinding> found = jdbcTemplate.query(query.sql(), (rs, row) -> {
                    ReconciliationFinding finding = new ReconciliationFinding();
                    finding.setCheck(query.check());
                    finding.setUserId(nullableLong(rs, "user_id"));
                    finding.setWalletId(nullableLong(rs, "wallet_id"));
                    finding.setReferenceType(rs.getString("reference_type"));
                    finding.setReferenceId(nullableLong(rs, "reference_id"));
                    finding.setExpectedValue(nullableLong(rs, "expected_value"));
                    finding.setActualValue(nullableLong(rs, "actual_value"));
                    finding.setDetail(truncate(rs.getString("detail"), 500));
                    return finding;
                });
                total += found.size();
                for (ReconciliationFinding finding : found) {
                    if (findings.size() < MAX_STORED_FINDINGS) {
                        findings.add(finding);
                    }
                }
            }

            run.setWalletsChecked(count("SELECT COUNT(*) FROM wallets"));
            run.setTransactionsChecked(count("SELECT COUNT(*) FROM wallet_transactions"));
            run.setTotalCreditBalance(count("SELECT COALESCE(SUM(credit_balance), 0) FROM wallets"));
            run.setTotalWinningsBalance(count("SELECT COALESCE(SUM(winnings_balance), 0) FROM wallets"));
            run.setFindingCount(total);
            run.setStatus(total == 0 ? ReconciliationStatus.OK : ReconciliationStatus.DISCREPANCIES);
        } catch (RuntimeException e) {
            // Un controle qui echoue ne prouve rien : on le dit, plutot que de
            // laisser croire a un bilan propre.
            log.error("Rapprochement comptable interrompu", e);
            findings.clear();
            run.setStatus(ReconciliationStatus.FAILED);
            run.setErrorMessage(truncate(e.getMessage(), 1000));
        }

        run.setFinishedAt(Instant.now());
        ReconciliationRun saved = runRepository.save(run);
        findings.forEach(finding -> finding.setRunId(saved.getId()));
        findingRepository.saveAll(findings);

        alertIfNeeded(saved, actorId);
        return ReconciliationRunResponse.from(saved, findings, walletService.htgRate());
    }

    @Transactional(readOnly = true)
    public Page<ReconciliationRunResponse> history(Pageable pageable) {
        long rate = walletService.htgRate();
        return runRepository.findAllByOrderByStartedAtDesc(pageable)
                .map(run -> ReconciliationRunResponse.from(run, null, rate));
    }

    @Transactional(readOnly = true)
    public ReconciliationRunResponse detail(Long runId) {
        ReconciliationRun run = runRepository.findById(runId)
                .orElseThrow(() -> ApiException.notFound("Rapprochement"));
        return ReconciliationRunResponse.from(run, findingRepository.findByRunIdOrderByIdAsc(runId),
                walletService.htgRate());
    }

    private void alertIfNeeded(ReconciliationRun run, Long actorId) {
        switch (run.getStatus()) {
            case OK -> log.info("Rapprochement {} : aucun ecart sur {} portefeuille(s), {} mouvement(s)",
                    run.getId(), run.getWalletsChecked(), run.getTransactionsChecked());
            case DISCREPANCIES -> {
                log.error("RAPPROCHEMENT {} : {} ECART(S) COMPTABLE(S) - voir "
                        + "GET /api/admin/reconciliation/runs/{}", run.getId(), run.getFindingCount(),
                        run.getId());
                auditService.record(actorId, "RECONCILIATION_DISCREPANCIES", "ReconciliationRun",
                        String.valueOf(run.getId()), null, run.getFindingCount() + " ecart(s)");
                notificationService.notifyAdmins(NotificationMessages.reconciliationAlert(
                        run.getId(), "ecarts releves", run.getFindingCount(), null));
            }
            case FAILED -> {
                auditService.record(actorId, "RECONCILIATION_FAILED", "ReconciliationRun",
                        String.valueOf(run.getId()), null, run.getErrorMessage());
                notificationService.notifyAdmins(NotificationMessages.reconciliationAlert(
                        run.getId(), "controle interrompu", 0, run.getErrorMessage()));
            }
        }
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
