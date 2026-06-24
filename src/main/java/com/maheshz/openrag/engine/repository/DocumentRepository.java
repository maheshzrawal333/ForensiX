package com.maheshz.openrag.engine.repository;

import com.maheshz.openrag.engine.domain.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<KnowledgeDocument, String> {
    // Allows us to look up a document by its Async Job ID
    Optional<KnowledgeDocument> findByJobId(String jobId);
}