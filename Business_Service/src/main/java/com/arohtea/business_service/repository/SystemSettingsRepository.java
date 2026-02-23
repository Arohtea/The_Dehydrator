package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.SystemSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemSettingsRepository extends JpaRepository<SystemSettings, String> {
}
