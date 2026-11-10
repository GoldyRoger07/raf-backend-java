package com.raikiservice.raf.notification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.notification.domain.AnnouncementAudience;
import com.raikiservice.raf.notification.domain.AnnouncementStatus;
import com.raikiservice.raf.notification.service.AnnouncementService;
import com.raikiservice.raf.notification.web.dto.AnnouncementRequest;
import com.raikiservice.raf.notification.web.dto.AnnouncementResponse;
import com.raikiservice.raf.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Historique des messages du back-office : recherche, filtres et tris. */
class AnnouncementListTest extends IntegrationTest {

    @Autowired private AnnouncementService announcementService;

    private Long create(User admin, User player, String title, boolean important) {
        // Programme dans une heure : reste SCHEDULED, rien n'est envoye pendant le test.
        return announcementService.create(admin.getId(), false, new AnnouncementRequest(
                AnnouncementAudience.ONE_PLAYER, player.getId(), false, important, true, false, false,
                title, "Texte", null, Instant.now().plus(1, ChronoUnit.HOURS), null)).id();
    }

    private List<Long> list(String query, Boolean important, Sort sort) {
        return announcementService.list(query, AnnouncementStatus.SCHEDULED, AnnouncementAudience.ONE_PLAYER,
                        important, PageRequest.of(0, 50, sort))
                .getContent().stream().map(AnnouncementResponse::id).toList();
    }

    @Test
    void searchesTitleAndRecipientAndSortsByTitle() {
        User admin = data.admin();
        User player = data.player();
        String marker = UUID.randomUUID().toString().substring(0, 8);
        Long b = create(admin, player, "B " + marker, false);
        Long a = create(admin, player, "A " + marker, true);

        assertThat(list(marker, null, Sort.by("title"))).containsExactly(a, b);
        assertThat(list(marker, null, Sort.by(Sort.Direction.DESC, "title"))).containsExactly(b, a);
        assertThat(list(marker, true, Sort.unsorted())).containsExactly(a);
        assertThat(list(player.fullName(), null, Sort.unsorted())).containsExactlyInAnyOrder(a, b);
        for (String field : List.of("scheduledAt", "audience", "status")) {
            assertThat(list(marker, null, Sort.by(field))).as(field).hasSize(2);
        }
    }

    @Test
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> list(null, null, Sort.by("body"))).isInstanceOf(ApiException.class);
    }
}
