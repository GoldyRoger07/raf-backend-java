package com.raikiservice.raf.admin;

import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.admin.service.AuditQueryService;
import com.raikiservice.raf.admin.web.dto.AuditEntryResponse;
import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.RoleRepository;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.JwtService;
import com.raikiservice.raf.game.service.AdminGameService;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AdminGameSessionResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.SubmitGameDto;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Back-office, phase 3 : comptes et roles, journal d'audit, parties signalees. */
class AdminPhase3Test extends IntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AuditQueryService auditQueryService;
    @Autowired private GameService gameService;
    @Autowired private AdminGameService adminGameService;

    // --- Comptes et roles ---------------------------------------------------------

    @Test
    void aSuperAdminCannotChangeTheirOwnRolesOrStatus() throws Exception {
        User boss = superAdmin();
        String token = token(boss);

        mvc.perform(post("/api/admin/users/" + boss.getId() + "/roles").header("Authorization", token)
                        .contentType("application/json").content("[\"PLAYER\"]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWN_ACCOUNT_LOCKED"));
        mvc.perform(post("/api/admin/users/" + boss.getId() + "/status").header("Authorization", token)
                        .contentType("application/json").content("\"SUSPENDED\""))
                .andExpect(status().isConflict());
    }

    @Test
    void anAccountKeepsAtLeastOneRoleAndRoleChangesAreAudited() throws Exception {
        User boss = superAdmin();
        User player = data.player();
        String token = token(boss);

        mvc.perform(post("/api/admin/users/" + player.getId() + "/roles").header("Authorization", token)
                        .contentType("application/json").content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLES_REQUIRED"));

        mvc.perform(post("/api/admin/users/" + player.getId() + "/roles").header("Authorization", token)
                        .contentType("application/json").content("[\"PLAYER\", \"MODERATOR\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("MODERATOR")));

        eventually(() -> {
            List<AuditEntryResponse> entries = auditQueryService.search("USER_ROLES_CHANGED", "User",
                    String.valueOf(player.getId()), null, PageRequest.of(0, 10)).getContent();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).actorName()).isEqualTo(boss.fullName());
            assertThat(entries.get(0).afterState()).contains("MODERATOR");
        });
    }

    @Test
    void accountsAreSearchableByPhoneDigitsAndStaffCanBeIsolated() throws Exception {
        User boss = superAdmin();
        User player = data.player();
        User moderator = data.moderator();
        String token = token(boss);

        mvc.perform(get("/api/admin/users").param("query", player.getPhone().substring(4))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(player.getId()));

        mvc.perform(get("/api/admin/users").param("query", moderator.getEmail()).param("staffOnly", "true")
                        .header("Authorization", token))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/admin/users").param("query", player.getEmail()).param("staffOnly", "true")
                        .header("Authorization", token))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void onlyTheSuperAdminManagesAccounts() throws Exception {
        mvc.perform(get("/api/admin/users").header("Authorization", token(data.admin())))
                .andExpect(status().isForbidden());
    }

    // --- Journal d'audit ------------------------------------------------------------

    @Test
    void theAuditTrailIsReadableAndFilterable() throws Exception {
        User admin = data.admin();
        User player = data.player();
        data.credit(player.getId(), BalanceType.CREDIT, 42);   // trace WALLET_ADJUSTED

        eventually(() -> assertThat(auditQueryService.search("WALLET_ADJUSTED", "User",
                String.valueOf(player.getId()), null, PageRequest.of(0, 10)).getContent()).hasSize(1));

        mvc.perform(get("/api/admin/audit").param("action", "WALLET_ADJUSTED")
                        .param("entityId", String.valueOf(player.getId()))
                        .header("Authorization", token(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].afterState").value(org.hamcrest.Matchers.containsString("42")));
        mvc.perform(get("/api/admin/audit/filters").header("Authorization", token(admin)))
                .andExpect(jsonPath("$.actions", hasItem("WALLET_ADJUSTED")));
        mvc.perform(get("/api/admin/audit").header("Authorization", token(data.moderator())))
                .andExpect(status().isForbidden());
    }

    // --- Parties signalees ------------------------------------------------------------

    @Test
    void flaggedGamesAreListedAcrossQuizzesUntilPrizesArePaid() throws Exception {
        Long category = data.categoryWithQuestions(50);
        User player = data.player();
        Long quizId = data.runningQuiz(category, 5, 1000, 1, player);

        GameStartResponse game = gameService.start(player.getId(), quizId, "127.0.0.1", "junit");
        Thread.sleep(100);
        List<SubmitGameDto.AnswerDto> answers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long questionId = game.questions().get(i).id();
            answers.add(new SubmitGameDto.AnswerDto(questionId, data.correctChoice(questionId), 10L * (i + 1)));
        }
        gameService.submit(player.getId(), new SubmitGameDto(game.sessionId(), answers, null));

        List<AdminGameSessionResponse> flagged = adminGameService.actionableFlagged(PageRequest.of(0, 100)).getContent();
        assertThat(flagged).anySatisfy(s -> {
            assertThat(s.id()).isEqualTo(game.sessionId());
            assertThat(s.quizId()).isEqualTo(quizId);
            assertThat(s.playerName()).isEqualTo(player.fullName());
            assertThat(s.quizTitle()).isNotBlank();
            assertThat(s.flagReason()).contains("ms");
        });

        // Invalidee, la partie n'est plus a traiter.
        gameService.invalidateByAdmin(null, game.sessionId(), "revue : reponses automatisees");
        assertThat(adminGameService.actionableFlagged(PageRequest.of(0, 100)).getContent())
                .noneSatisfy(s -> assertThat(s.id()).isEqualTo(game.sessionId()));
    }

    private User superAdmin() {
        User user = data.admin();
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        loaded.getRoles().add(roleRepository.findByName(RoleName.SUPER_ADMIN).orElseThrow());
        return userRepository.save(loaded);
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateAccessToken(userRepository.findById(user.getId()).orElseThrow());
    }
}
