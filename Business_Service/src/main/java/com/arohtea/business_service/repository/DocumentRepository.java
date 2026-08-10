package com.arohtea.business_service.repository;

import com.arohtea.business_service.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Document> findById(String id);
}
