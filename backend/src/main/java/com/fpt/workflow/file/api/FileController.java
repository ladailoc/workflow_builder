package com.fpt.workflow.file.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.file.domain.FileLinkOwnerType;
import com.fpt.workflow.file.domain.FileRef;
import com.fpt.workflow.file.domain.FileScanStatus;
import com.fpt.workflow.file.domain.StoredFile;
import com.fpt.workflow.file.repository.StoredFileRepository;
import com.fpt.workflow.file.service.FilePolicy;
import com.fpt.workflow.file.service.FileService;
import com.fpt.workflow.file.service.FileUpload;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

  private final FileService fileService;
  private final StoredFileRepository storedFileRepository;
  private final long maxFileSizeBytes;

  public FileController(
      FileService fileService,
      StoredFileRepository storedFileRepository,
      @Value("${platform.files.max-file-size-bytes:26214400}") long maxFileSizeBytes) {
    this.fileService = Objects.requireNonNull(fileService, "fileService");
    this.storedFileRepository =
        Objects.requireNonNull(storedFileRepository, "storedFileRepository");
    this.maxFileSizeBytes = maxFileSizeBytes;
  }

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<FileRef> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam("ownerType") FileLinkOwnerType ownerType,
      @RequestParam("ownerId") UUID ownerId,
      @RequestParam(value = "fieldKey", defaultValue = "attachment") String fieldKey,
      @RequestParam(value = "sensitive", defaultValue = "false") boolean sensitive)
      throws IOException {

    String originalName = file.getOriginalFilename();
    if (originalName == null || originalName.isBlank()) {
      originalName = "upload.bin";
    }

    String contentType = file.getContentType();
    if (contentType == null || contentType.isBlank()) {
      contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    java.util.Set<String> allowedTypes =
        new java.util.HashSet<>(
            java.util.List.of(
                MediaType.APPLICATION_PDF_VALUE,
                MediaType.IMAGE_PNG_VALUE,
                MediaType.IMAGE_JPEG_VALUE,
                MediaType.TEXT_PLAIN_VALUE,
                MediaType.APPLICATION_JSON_VALUE,
                MediaType.APPLICATION_OCTET_STREAM_VALUE));
    allowedTypes.add(contentType);

    FilePolicy policy =
        new FilePolicy(allowedTypes, maxFileSizeBytes, 10, Duration.ofDays(30), sensitive);

    FileUpload upload =
        new FileUpload(
            originalName,
            contentType,
            file.getSize(),
            file.getInputStream(),
            ownerType,
            ownerId,
            fieldKey,
            JsonNodeFactory.instance.objectNode());

    FileRef ref = fileService.upload(upload, policy);
    return ResponseEntity.status(HttpStatus.CREATED).body(ref);
  }

  @GetMapping("/{fileId}")
  public ResponseEntity<FileRef> getMetadata(@PathVariable UUID fileId) {
    StoredFile file =
        storedFileRepository
            .findById(fileId)
            .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));
    return ResponseEntity.ok(file.toRef());
  }

  @PostMapping("/{fileId}/scan")
  @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
  public ResponseEntity<FileRef> recordScan(
      @PathVariable UUID fileId, @RequestParam("status") FileScanStatus status) {
    FileRef updated = fileService.recordScan(fileId, status);
    return ResponseEntity.ok(updated);
  }

  @GetMapping("/{fileId}/download")
  public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID fileId) {
    StoredFile file =
        storedFileRepository
            .findById(fileId)
            .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

    InputStream inputStream = fileService.download(fileId);

    StreamingResponseBody responseBody =
        outputStream -> {
          try (inputStream) {
            inputStream.transferTo(outputStream);
          }
        };

    String mimeType = file.getMimeType();
    MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
    try {
      mediaType = MediaType.parseMediaType(mimeType);
    } catch (Exception ignored) {
    }

    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + file.getOriginalName() + "\"")
        .contentType(mediaType)
        .contentLength(file.getSizeBytes())
        .body(responseBody);
  }
}
