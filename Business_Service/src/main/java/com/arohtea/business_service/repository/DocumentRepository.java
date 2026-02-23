package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, String> {
}
