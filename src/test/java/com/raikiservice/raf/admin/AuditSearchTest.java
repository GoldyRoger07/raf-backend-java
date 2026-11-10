package com.raikiservice.raf.admin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.admin.service.AuditQueryService;
import com.raikiservice.raf.admin.web.dto.AuditEntryResponse;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Journal d'audit : recherche libre, periode et tris. */
class AuditSearchTest extends IntegrationTest {

    @Autowired private AuditService auditService;
    @Autowired private AuditQueryService auditQueryService;

    private List<AuditEntryResponse> search(String query, LocalDate from, LocalDate to, Sort sort) {
        return auditQueryService.search(null, null, null, null, query, from, to, PageRequest.of(0, 50, sort))
                .getContent();
    }

    @Test
    void searchesChangesAndAuthorsWithinAPeriod() throws Exception {
        User author = data.admin();
        String marker = UUID.randomUUID().toString();
        auditService.record(author.getId(), "TEST_B", "Thing", "1", null, "{\"note\":\"" + marker + "\"}");
        auditService.record(author.getId(), "TEST_A", "Thing", "2", null, "{\"note\":\"" + marker + "\"}");

        eventually(() -> assertThat(search(marker, null, null, Sort.unsorted())).hasSize(2));

        assertThat(search(marker, null, null, Sort.by("action")))
                .extracting(AuditEntryResponse::action).containsExactly("TEST_A", "TEST_B");
        assertThat(search(marker, null, null, Sort.by(Sort.Direction.DESC, "action")))
                .extracting(AuditEntryResponse::action).containsExactly("TEST_B", "TEST_A");
        // Par defaut, le plus recent d'abord.
        assertThat(search(marker, null, null, Sort.unsorted()))
                .extracting(AuditEntryResponse::action).containsExactly("TEST_A", "TEST_B");

        assertThat(search(author.getEmail(), null, null, Sort.unsorted())).hasSizeGreaterThanOrEqualTo(2);

        LocalDate today = LocalDate.now(ZoneId.of("America/Port-au-Prince"));
        assertThat(search(marker, today, today, Sort.by("entityType"))).hasSize(2);
        assertThat(search(marker, today.plusDays(1), null, Sort.unsorted())).isEmpty();
    }

    @Test
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> search(null, null, null, Sort.by("userAgent"))).isInstanceOf(ApiException.class);
    }
}
