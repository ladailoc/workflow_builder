package com.fpt.workflow.file;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fpt.workflow.file.domain.*;
import com.fpt.workflow.file.repository.*;
import com.fpt.workflow.file.service.*;
import com.fpt.workflow.file.storage.*;
import com.fpt.workflow.security.*;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

class FileServiceTest {
  private final StoredFileRepository files = mock(StoredFileRepository.class);
  private final FileLinkRepository links = mock(FileLinkRepository.class);
  private final ObjectStorage storage = mock(ObjectStorage.class);
  private final FileDownloadAuthorizer authorizer = mock(FileDownloadAuthorizer.class);
  private final ActorContextProvider actors = mock(ActorContextProvider.class);
  private final UuidGenerator uuids = mock(UuidGenerator.class);
  private final PlatformClock clock = mock(PlatformClock.class);
  private final UUID actorId = UUID.randomUUID();
  private final UUID ownerId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
  private FileService service;

  @BeforeEach
  void setUp() {
    when(actors.requireActor())
        .thenReturn(new ActorContext(actorId, "actor", Set.of(RoleKey.USER), Set.of()));
    when(clock.now()).thenReturn(now);
    when(uuids.generate()).thenReturn(UUID.randomUUID(), UUID.randomUUID());
    when(storage.provider()).thenReturn("TEST");
    when(storage.put(anyString(), any(), anyLong()))
        .thenAnswer(a -> new StoredObject(a.getArgument(0), "bucket"));
    when(files.saveAndFlush(any())).thenAnswer(a -> a.getArgument(0));
    when(links.saveAndFlush(any())).thenAnswer(a -> a.getArgument(0));
    service =
        new FileService(
            new FileMetadataTransactions(files, links, authorizer), storage, actors, uuids, clock);
  }

  @Test
  void uploadPersistsMetadataReferenceOnlyAndSerializesFileRef() throws Exception {
    byte[] bytes = "purchase-order".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    FileRef ref = service.upload(upload("po.pdf", "application/pdf", bytes), policy(100, 2));

    assertThat(ref.originalName()).isEqualTo("po.pdf");
    assertThat(ref.size()).isEqualTo(bytes.length);
    assertThat(ref.scanStatus()).isEqualTo(FileScanStatus.PENDING_SCAN);
    assertThat(ref.checksum()).hasSize(64);
    ArgumentCaptor<StoredFile> saved = ArgumentCaptor.forClass(StoredFile.class);
    verify(files).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getMetadataJson().path("category").asText()).isEqualTo("evidence");
    assertThat(saved.getValue().getMetadataJson().toString()).doesNotContain("purchase-order");

    String json =
        JsonMapper.builder().addModule(new JavaTimeModule()).build().writeValueAsString(ref);
    assertThat(json)
        .contains(ref.fileId().toString(), "application/pdf", "PENDING_SCAN", "storageKey");
    assertThat(json).doesNotContain("cHVyY2hhc2Utb3JkZXI=");
  }

  @Test
  void rejectsInvalidMimeSizeAndCountBeforeStorage() {
    byte[] bytes = new byte[5];
    assertThatThrownBy(
            () -> service.upload(upload("x.exe", "application/octet-stream", bytes), policy(10, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MIME");
    assertThatThrownBy(
            () -> service.upload(upload("x.pdf", "application/pdf", bytes), policy(4, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size");
    when(links.countByOwnerTypeAndOwnerIdAndFieldKey(any(), any(), anyString())).thenReturn(1L);
    assertThatThrownBy(
            () -> service.upload(upload("x.pdf", "application/pdf", bytes), policy(10, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("count");
    verify(storage, never()).put(anyString(), any(), anyLong());
  }

  @Test
  void quarantinedFileAndPermissionBothBlockDownload() {
    StoredFile file = storedFile(false);
    when(files.findById(file.getId())).thenReturn(Optional.of(file));
    assertThatThrownBy(() -> service.download(file.getId()))
        .isInstanceOf(AccessDeniedException.class);
    verify(storage, never()).get(anyString());

    file.recordScan(FileScanStatus.CLEAN);
    when(links.findAllByFileId(file.getId())).thenReturn(List.of());
    when(authorizer.mayDownload(any(), eq(file), anyList())).thenReturn(false);
    assertThatThrownBy(() -> service.download(file.getId()))
        .isInstanceOf(AccessDeniedException.class);
    verify(storage, never()).get(anyString());
  }

  @Test
  void cleanAuthorizedFileCanBeDownloaded() throws Exception {
    StoredFile file = storedFile(false);
    file.recordScan(FileScanStatus.CLEAN);
    when(files.findById(file.getId())).thenReturn(Optional.of(file));
    when(links.findAllByFileId(file.getId())).thenReturn(List.of());
    when(authorizer.mayDownload(any(), eq(file), anyList())).thenReturn(true);
    when(storage.get(file.getStorageKey())).thenReturn(new ByteArrayInputStream(new byte[] {1, 2}));
    assertThat(service.download(file.getId()).readAllBytes()).containsExactly(1, 2);
  }

  private FileUpload upload(String name, String mime, byte[] bytes) {
    var metadata = JsonMapper.builder().build().createObjectNode().put("category", "evidence");
    return new FileUpload(
        name,
        mime,
        bytes.length,
        new ByteArrayInputStream(bytes),
        FileLinkOwnerType.TICKET_REVISION,
        ownerId,
        "attachments",
        metadata);
  }

  private FilePolicy policy(long size, int count) {
    return new FilePolicy(Set.of("application/pdf"), size, count, Duration.ofDays(30), false);
  }

  private StoredFile storedFile(boolean sensitive) {
    return StoredFile.uploaded(
        UUID.randomUUID(),
        "x.pdf",
        "application/pdf",
        2,
        "abc",
        "TEST",
        null,
        "safe/x.pdf",
        actorId,
        now,
        now.plus(Duration.ofDays(1)),
        sensitive,
        JsonMapper.builder().build().createObjectNode());
  }
}
