package com.raikiservice.raf.common.settings;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {

    Optional<AppSetting> findByKey(String key);
}
