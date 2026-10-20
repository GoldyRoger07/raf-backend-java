package com.raikiservice.raf.common.settings;

import java.time.Instant;

/** Un parametre applicatif, tel que l'affiche le back-office. */
public record SettingResponse(String key, String value, String description, long minValue, Instant updatedAt) {

    static SettingResponse from(AppSetting setting) {
        return new SettingResponse(setting.getKey(), setting.getValue(), setting.getDescription(),
                SettingKeys.minValue(setting.getKey()), setting.getUpdatedAt());
    }
}
