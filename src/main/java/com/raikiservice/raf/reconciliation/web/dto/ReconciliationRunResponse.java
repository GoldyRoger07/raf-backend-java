package com.raikiservice.raf.reconciliation.web.dto;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.reconciliation.domain.ReconciliationCheck;
import com.raikiservice.raf.reconciliation.domain.ReconciliationFinding;
import com.raikiservice.raf.reconciliation.domain.ReconciliationRun;
import com.raikiservice.raf.reconciliation.domain.ReconciliationStatus;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;

/**
 * Bilan d'un rapprochement. {@code findings} n'est rempli que dans la vue
 * detaillee (et limite aux 500 premiers ecarts ; {@code findingCount} est exact).
 */
public record ReconciliationRunResponse(
        Long id,
        ReconciliationTrigger trigger,
        Long triggeredBy,
        ReconciliationStatus status,
        Instant startedAt,
        Instant finishedAt,
        long walletsChecked,
        long transactionsChecked,
        int findingCount,
        long totalCreditBalance,
        long totalCreditBalanceHtg,
        long totalWinningsBalanceHtg,
        String errorMessage,
        List<Finding> findings) {

    public record Finding(ReconciliationCheck check, String checkDescription, Long userId,
            Long walletId, String referenceType, Long referenceId, Long expectedValue,
            Long actualValue, String detail) {
    }

    public static ReconciliationRunResponse from(ReconciliationRun run,
            List<ReconciliationFinding> findings, long htgRate) {
        return new ReconciliationRunResponse(
                run.getId(),
                run.getTrigger(),
                run.getTriggeredBy(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getWalletsChecked(),
                run.getTransactionsChecked(),
                run.getFindingCount(),
                run.getTotalCreditBalance(),
                run.getTotalCreditBalance() * htgRate,
                run.getTotalWinningsBalance(),
                run.getErrorMessage(),
                findings == null ? null : findings.stream()
                        .map(f -> new Finding(f.getCheck(), f.getCheck().getDescription(),
                                f.getUserId(), f.getWalletId(), f.getReferenceType(),
                                f.getReferenceId(), f.getExpectedValue(), f.getActualValue(),
                                f.getDetail()))
                        .toList());
    }
}
