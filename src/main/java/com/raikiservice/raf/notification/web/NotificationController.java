package com.raikiservice.raf.notification.web;

import java.util.List;
import java.util.Map;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.common.util.RequestContext;
import com.raikiservice.raf.notification.service.NotificationInboxService;
import com.raikiservice.raf.notification.service.NotificationPreferenceService;
import com.raikiservice.raf.notification.web.dto.NotificationResponse;
import com.raikiservice.raf.notification.web.dto.PreferenceUpdateDto;
import com.raikiservice.raf.notification.web.dto.PreferenceView;
import com.raikiservice.raf.notification.web.dto.PushSubscriptionDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationInboxService inboxService;
    private final NotificationPreferenceService preferenceService;

    @GetMapping
    @Operation(summary = "Mes notifications, les plus recentes d'abord")
    public Page<NotificationResponse> list(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @PageableDefault(size = 20) Pageable pageable) {
        return inboxService.list(user.getId(), unreadOnly, pageable);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Nombre de notifications non lues (pastille)")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal UserPrincipal user) {
        return Map.of("unread", inboxService.unreadCount(user.getId()));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Marquer une notification comme lue")
    public MessageResponse markRead(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long id) {
        inboxService.markRead(user.getId(), id);
        return new MessageResponse("Notification lue.");
    }

    @PostMapping("/read-all")
    @Operation(summary = "Tout marquer comme lu")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal UserPrincipal user) {
        return Map.of("marked", inboxService.markAllRead(user.getId()));
    }

    @GetMapping("/preferences")
    @Operation(summary = "Canaux actifs par type de notification (email, push)")
    public List<PreferenceView> preferences(@AuthenticationPrincipal UserPrincipal user) {
        return preferenceService.list(user.getId(), isAdmin(user));
    }

    @PutMapping("/preferences")
    @Operation(summary = "Activer ou couper l'email / le push pour certains types")
    public List<PreferenceView> updatePreferences(@AuthenticationPrincipal UserPrincipal user,
            @RequestBody List<@Valid PreferenceUpdateDto> updates) {
        preferenceService.update(user.getId(), updates);
        return preferenceService.list(user.getId(), isAdmin(user));
    }

    @GetMapping("/push/public-key")
    @Operation(summary = "Cle publique VAPID, pour abonner le navigateur au push")
    public Map<String, Object> vapidPublicKey() {
        String key = inboxService.vapidPublicKey();
        return key == null ? Map.of("configured", false)
                : Map.of("configured", true, "publicKey", key);
    }

    @PostMapping("/push/subscriptions")
    @Operation(summary = "Abonner ce navigateur aux notifications push")
    public ResponseEntity<MessageResponse> subscribe(@AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody PushSubscriptionDto request, HttpServletRequest http) {
        inboxService.subscribe(user.getId(), request, RequestContext.userAgent(http));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MessageResponse("Notifications push activees."));
    }

    @DeleteMapping("/push/subscriptions")
    @Operation(summary = "Desabonner ce navigateur")
    public MessageResponse unsubscribe(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam @NotBlank String endpoint) {
        inboxService.unsubscribe(user.getId(), endpoint);
        return new MessageResponse("Notifications push desactivees.");
    }

    private static boolean isAdmin(UserPrincipal user) {
        return user.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }
}
