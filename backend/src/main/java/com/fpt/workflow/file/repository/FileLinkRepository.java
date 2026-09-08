package com.fpt.workflow.file.repository;

import com.fpt.workflow.file.domain.FileLink;
import com.fpt.workflow.file.domain.FileLinkOwnerType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileLinkRepository extends JpaRepository<FileLink, UUID> {
  List<FileLink> findAllByFileId(UUID fileId);

  long countByOwnerTypeAndOwnerIdAndFieldKey(
      FileLinkOwnerType ownerType, UUID ownerId, String fieldKey);
}
