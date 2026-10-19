package com.raikiservice.raf.notification.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.notification.domain.NotificationChannel;
import com.raikiservice.raf.notification.domain.NotificationPreference;
import com.raikiservice.raf.notification.domain.NotificationType;
import com.raikiservice.raf.notification.repository.NotificationPreferenceRepository;
import com.raikiservice.raf.notification.web.dto.PreferenceUpdateDto;
import com.raikiservice.raf.notification.web.dto.PreferenceView;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canaux actifs par type de notification. Seuls les choix explicites du joueur
 * sont stockes ; sans choix, ce sont les canaux par defaut du type qui valent.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private static final List<NotificationChannel> CONFIGURABLE =
            List.of(NotificationChannel.EMAIL, NotificationChannel.PUSH);

    private final NotificationPreferenceRepository preferenceRepository;

    /** Canaux d'envoi (email, push) actifs pour ce joueur et ce type. */
    @Transactional(readOnly = true)
    public Set<NotificationChannel> enabledChannels(Long userId, NotificationType type) {
        Set<NotificationChannel> channels = type.getDefaultChannels().isEmpty()
                ? EnumSet.noneOf(NotificationChannel.class)
                : EnumSet.copyOf(type.getDefaultChannels());

        for (NotificationPreference preference : preferenceRepository.findByUserId(userId)) {
            if (preference.getType() != type) {
                continue;
            }
            if (preference.isEnabled()) {
                channels.add(preference.getChannel());
            } else {
                channels.remove(preference.getChannel());
            }
        }
        return channels;
    }

    @Transactional(readOnly = true)
    public List<PreferenceView> list(Long userId, boolean admin) {
        List<PreferenceView> views = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            if (type == NotificationType.ADMIN_RECONCILIATION_ALERT && !admin) {
                continue;
            }
            Set<NotificationChannel> enabled = enabledChannels(userId, type);
            for (NotificationChannel channel : CONFIGURABLE) {
                views.add(new PreferenceView(type, type.getDescription(), channel,
                        enabled.contains(channel), type.getDefaultChannels().contains(channel)));
            }
        }
        return views;
    }

    @Transactional
    public void update(Long userId, List<PreferenceUpdateDto> updates) {
        for (PreferenceUpdateDto update : updates) {
            if (!CONFIGURABLE.contains(update.channel())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Les notifications dans l'application ne peuvent pas etre desactivees.");
            }
            NotificationPreference preference = preferenceRepository
                    .findByUserIdAndTypeAndChannel(userId, update.type(), update.channel())
                    .orElseGet(() -> {
                        NotificationPreference created = new NotificationPreference();
                        created.setUserId(userId);
                        created.setType(update.type());
                        created.setChannel(update.channel());
                        return created;
                    });
            preference.setEnabled(update.enabled());
            preferenceRepository.save(preference);
        }
    }
}
