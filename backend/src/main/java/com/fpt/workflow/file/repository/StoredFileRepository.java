package com.fpt.workflow.file.repository;

import com.fpt.workflow.file.domain.StoredFile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {}
