package com.arohtea.business_service.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "analysis_tasks")
public class AnalysisTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String documentId;

    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String argumentChain;

    @Column(columnDefinition = "TEXT")
    private String logicFlaws;

    @Column(columnDefinition = "TEXT")
    private String crossValidation;

    private Integer progress = 0;
    private String currentStep;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
