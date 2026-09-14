package com.fpt.workflow.file.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.file.domain.FileLinkOwnerType;
import com.fpt.workflow.file.domain.FileRef;
import com.fpt.workflow.file.domain.FileScanStatus;
import com.fpt.workflow.file.domain.StoredFile;
import com.fpt.workflow.file.repository.StoredFileRepository;
import com.fpt.workflow.file.service.FileService;
import com.fpt.workflow.security.SpringSecurityActorContextProvider;
import com.fpt.workflow.security.audit.AuditPrincipalProvider;
import com.fpt.workflow.security.config.MethodSecurityConfiguration;
import com.fpt.workflow.security.config.WebSecurityConfiguration;
import com.fpt.workflow.security.testing.WithMockActor;
import com.fpt.workflow.security.web.ProblemAccessDeniedHandler;
import com.fpt.workflow.security.web.ProblemAuthenticationEntryPoint;
import com.fpt.workflow.security.web.SecurityProblemWriter;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.ApiExceptionHandler;
import com.fpt.workflow.shared.api.ApiProblemFactory;
import com.fpt.workflow.shared.api.RequestCorrelationFilter;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.testing.FixedPlatformClock;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = FileController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({
  FileControllerTest.TestConfig.class,
  WebSecurityConfiguration.class,
  MethodSecurityConfiguration.class,
  RequestCorrelationFilter.class,
  ApiExceptionHandler.class,
  ApiProblemFactory.class,
  ProblemAuthenticationEntryPoint.class,
  ProblemAccessDeniedHandler.class,
  SecurityProblemWriter.class,
  SpringSecurityActorContextProvider.class,
  AuditPrincipalProvider.class
})
class FileControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private FileService fileService;
  @MockitoBean private StoredFileRepository storedFileRepository;
  @MockitoBean private com.fpt.workflow.file.service.FileMetadataTransactions fileMetadataTransactions;

  @Test
  @WithMockActor(roles = "USER")
  void getMetadataAuthorizedReturnsFileMetadataResponseWithoutStorageKey() throws Exception {
    UUID fileId = UUID.randomUUID();
    UUID uploadedBy = UUID.randomUUID();
    StoredFile file =
        StoredFile.uploaded(
            fileId,
            "document.pdf",
            "application/pdf",
            2048,
            "hashabc",
            "LOCAL",
            "bucket",
            "secret-internal-storage-key/document.pdf",
            uploadedBy,
            Instant.now(),
            null,
            false,
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());

    org.mockito.Mockito.when(fileMetadataTransactions.authorizeMetadata(org.mockito.ArgumentMatchers.eq(fileId), org.mockito.ArgumentMatchers.any()))
        .thenReturn(file);

    mockMvc
        .perform(get("/api/v1/files/{fileId}", fileId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fileId").value(fileId.toString()))
        .andExpect(jsonPath("$.originalName").value("document.pdf"))
        .andExpect(jsonPath("$.mimeType").value("application/pdf"))
        .andExpect(jsonPath("$.size").value(2048))
        .andExpect(jsonPath("$.storageKey").doesNotExist())
        .andExpect(jsonPath("$.storageBucket").doesNotExist())
        .andExpect(jsonPath("$.storageProvider").doesNotExist());
  }

  @Test
  @WithMockActor(roles = "USER")
  void getMetadataUnauthorizedReturnsForbidden() throws Exception {
    UUID fileId = UUID.randomUUID();
    org.mockito.Mockito.when(fileMetadataTransactions.authorizeMetadata(org.mockito.ArgumentMatchers.eq(fileId), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new org.springframework.security.access.AccessDeniedException("File access is forbidden"));

    mockMvc
        .perform(get("/api/v1/files/{fileId}", fileId))
        .andExpect(status().isForbidden());
  }

  @Test
  void getMetadataUnauthenticatedReturnsUnauthorized() throws Exception {
    UUID fileId = UUID.randomUUID();
    mockMvc
        .perform(get("/api/v1/files/{fileId}", fileId))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockActor(roles = "USER")
  void uploadReturnsCreatedFileRef() throws Exception {
    UUID fileId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    FileRef ref =
        new FileRef(
            fileId,
            "invoice.pdf",
            "application/pdf",
            1024,
            "hash123",
            fileId + "/invoice.pdf",
            UUID.randomUUID(),
            Instant.now(),
            FileScanStatus.PENDING_SCAN);

    when(fileService.upload(any(), any())).thenReturn(ref);

    MockMultipartFile multipartFile =
        new MockMultipartFile(
            "file", "invoice.pdf", "application/pdf", "dummy pdf content".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/files/upload")
                .file(multipartFile)
                .param("ownerType", FileLinkOwnerType.TICKET_REVISION.name())
                .param("ownerId", ownerId.toString())
                .param("fieldKey", "invoice_doc")
                .contentType(MediaType.MULTIPART_FORM_DATA))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.fileId").value(fileId.toString()))
        .andExpect(jsonPath("$.originalName").value("invoice.pdf"))
        .andExpect(jsonPath("$.mimeType").value("application/pdf"))
        .andExpect(jsonPath("$.scanStatus").value("PENDING_SCAN"));
  }

  @Test
  @WithMockActor(roles = "OPERATOR")
  void recordScanReturnsUpdatedStatus() throws Exception {
    UUID fileId = UUID.randomUUID();
    FileRef ref =
        new FileRef(
            fileId,
            "invoice.pdf",
            "application/pdf",
            1024,
            "hash123",
            fileId + "/invoice.pdf",
            UUID.randomUUID(),
            Instant.now(),
            FileScanStatus.CLEAN);

    when(fileService.recordScan(eq(fileId), eq(FileScanStatus.CLEAN))).thenReturn(ref);

    mockMvc
        .perform(post("/api/v1/files/{fileId}/scan", fileId).param("status", "CLEAN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fileId").value(fileId.toString()))
        .andExpect(jsonPath("$.scanStatus").value("CLEAN"));

    verify(fileService).recordScan(eq(fileId), eq(FileScanStatus.CLEAN));
  }

  @Test
  @WithMockActor(roles = "USER")
  void downloadReturnsStreamingBodyWithHeaders() throws Exception {
    UUID fileId = UUID.randomUUID();
    StoredFile stored =
        StoredFile.uploaded(
            fileId,
            "invoice.pdf",
            "application/pdf",
            14,
            "hash123",
            "LOCAL",
            null,
            fileId + "/invoice.pdf",
            UUID.randomUUID(),
            Instant.now(),
            null,
            false,
            JsonNodeFactory.instance.objectNode());

    when(storedFileRepository.findById(fileId)).thenReturn(Optional.of(stored));
    when(fileService.download(fileId))
        .thenReturn(new ByteArrayInputStream("dummy contents".getBytes()));

    mockMvc
        .perform(get("/api/v1/files/{fileId}/download", fileId))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice.pdf\""));
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    PlatformClock platformClock() {
      return new FixedPlatformClock(Instant.parse("2026-09-09T00:00:00Z"));
    }

    @Bean
    UuidGenerator uuidGenerator() {
      return UUID::randomUUID;
    }
  }
}
