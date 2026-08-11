package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.SystemSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 系统设置单例数据访问接口，固定 ID 为 `default`。
 */
public interface SystemSettingsRepository extends JpaRepository<SystemSettings, String> {
}
