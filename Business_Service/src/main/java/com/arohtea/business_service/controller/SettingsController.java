package com.arohtea.business_service.controller;

import com.arohtea.business_service.model.SystemSettings;
import com.arohtea.business_service.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
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
        SystemSettings s = settingsService.get();
        Map<String, Object> r = new LinkedHashMap<>();
        String key = s.getApiKey();
        r.put("apiKey", key != null && key.length() > 8 ? key.substring(0, 8) + "***" : key);
        r.put("model", s.getModel());
        r.put("mapWorkers", s.getMapWorkers());
        r.put("chunkSize", s.getChunkSize());
        r.put("chunkOverlap", s.getChunkOverlap());
        return r;
    }

    @PutMapping
    public SystemSettings save(@RequestBody SystemSettings input) {
        return settingsService.save(input);
    }
}
