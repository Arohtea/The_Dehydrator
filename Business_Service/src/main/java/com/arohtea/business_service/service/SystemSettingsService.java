package com.arohtea.business_service.service;

import com.arohtea.business_service.model.SystemSettings;
import com.arohtea.business_service.repository.SystemSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final SystemSettingsRepository repo;

    public SystemSettings get() {
        return repo.findById("default").orElse(new SystemSettings());
    }

    public SystemSettings save(SystemSettings input) {
        SystemSettings existing = repo.findById("default").orElse(new SystemSettings());
        if (input.getApiKey() != null && !input.getApiKey().endsWith("***")) existing.setApiKey(input.getApiKey());
        if (input.getModel() != null) existing.setModel(input.getModel());
        if (input.getMapWorkers() != null) existing.setMapWorkers(input.getMapWorkers());
        if (input.getChunkSize() != null) existing.setChunkSize(input.getChunkSize());
        if (input.getChunkOverlap() != null) existing.setChunkOverlap(input.getChunkOverlap());
        return repo.save(existing);
    }
}
