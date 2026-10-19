package com.raikiservice.raf.common.settings;

import com.raikiservice.raf.common.domain.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Parametre modifiable sans redeploiement. */
@Getter
@Setter
@Entity
@Table(name = "app_settings")
public class AppSetting extends AuditableEntity {

    @Column(name = "setting_key", nullable = false, length = 120, unique = true)
    private String key;

    @Column(name = "setting_value", length = 1000)
    private String value;

    @Column(length = 255)
    private String description;
}
