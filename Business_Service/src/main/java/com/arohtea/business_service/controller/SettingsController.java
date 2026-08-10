package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.SystemSettings;
import com.arohtea.business_service.model.SystemSettingsUpdate;
import com.arohtea.business_service.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SystemSettingsService settingsService;

    @GetMapping
    public Map<String, Object> get() {
        return toResponse(settingsService.get());
    }

    @PutMapping
    public ResponseEntity<?> save(@Valid @RequestBody SystemSettingsUpdate input) {
        try {
            return ResponseEntity.ok(toResponse(settingsService.save(input)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    private Map<String, Object> toResponse(SystemSettings s) {
        Map<String, Object> r = new LinkedHashMap<>();
        String key = s.getApiKey();
        r.put("apiKeyConfigured", key != null && !key.isBlank());
        r.put("apiKeyPreview", key != null && key.length() > 8 ? key.substring(0, 8) + "***" : null);
        r.put("model", s.getModel());
        r.put("mapWorkers", s.getMapWorkers());
        r.put("chunkSize", s.getChunkSize());
        r.put("chunkOverlap", s.getChunkOverlap());
        return r;
    }
}
