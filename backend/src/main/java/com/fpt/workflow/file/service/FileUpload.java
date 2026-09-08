package com.fpt.workflow.file.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.file.domain.FileLinkOwnerType;
import java.io.InputStream;
import java.util.UUID;

public record FileUpload(
    String originalName,
    String mimeType,
    long size,
    InputStream content,
    FileLinkOwnerType ownerType,
    UUID ownerId,
    String fieldKey,
    JsonNode metadata) {}
