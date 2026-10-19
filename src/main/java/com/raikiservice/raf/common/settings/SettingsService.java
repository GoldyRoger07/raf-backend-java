package com.raikiservice.raf.common.settings;

import com.raikiservice.raf.common.audit.AuditService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture des parametres applicatifs.
 *
 * <p>Une valeur absente ou illisible ne doit jamais faire tomber une operation
 * financiere : on journalise et on retombe sur la valeur par defaut fournie par
 * l'appelant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final AppSettingRepository appSettingRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public long getLong(String key, long defaultValue) {
        return appSettingRepository.findByKey(key)
                .map(AppSetting::getValue)
                .map(value -> parse(key, value, defaultValue))
                .orElse(defaultValue);
    }

    @Transactional(readOnly = true)
    public String getString(String key, String defaultValue) {
        return appSettingRepository.findByKey(key)
                .map(AppSetting::getValue)
                .orElse(defaultValue);
    }

    /** Modification tracee : ces valeurs pilotent des montants reels. */
    @Transactional
    public AppSetting update(Long actorUserId, String key, String value) {
        AppSetting setting = appSettingRepository.findByKey(key)
                .orElseGet(() -> {
                    AppSetting created = new AppSetting();
                    created.setKey(key);
                    return created;
                });

        String previous = setting.getValue();
        setting.setValue(value);
        AppSetting saved = appSettingRepository.save(setting);

        auditService.record(actorUserId, "SETTING_UPDATED", "AppSetting", key, previous, value);
        return saved;
    }

    private long parse(String key, String value, long defaultValue) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.error("Parametre {} illisible ('{}'), valeur par defaut {} utilisee",
                    key, value, defaultValue);
            return defaultValue;
        }
    }
}
