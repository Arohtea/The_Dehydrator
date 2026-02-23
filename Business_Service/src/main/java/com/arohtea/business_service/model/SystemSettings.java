package com.arohtea.business_service.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "system_settings")
public class SystemSettings {

    @Id
    private String id = "default";

    private String apiKey;
    private String model;
    private Integer mapWorkers;
    private Integer chunkSize;
    private Integer chunkOverlap;
}
