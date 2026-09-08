package com.fpt.workflow.file.service;

import com.fpt.workflow.file.domain.FileLink;
import com.fpt.workflow.file.domain.StoredFile;
import com.fpt.workflow.security.ActorContext;
import java.util.List;

public interface FileDownloadAuthorizer {
  boolean mayDownload(ActorContext actor, StoredFile file, List<FileLink> links);
}
