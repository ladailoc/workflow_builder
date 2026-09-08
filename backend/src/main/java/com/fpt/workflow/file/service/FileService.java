package com.fpt.workflow.file.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.file.domain.*;
import com.fpt.workflow.file.storage.*;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.io.*;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FileService {
  private final FileMetadataTransactions transactions;
  private final ObjectStorage storage;
  private final ActorContextProvider actors;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  public FileService(
      FileMetadataTransactions transactions,
      ObjectStorage storage,
      ActorContextProvider actors,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.transactions = transactions;
    this.storage = storage;
    this.actors = actors;
    this.uuids = uuids;
    this.clock = clock;
  }

  public FileRef upload(FileUpload upload, FilePolicy policy) {
    ActorContext actor = actors.requireActor();
    validate(upload, policy);
    transactions.verifyCapacity(upload, policy.maxFileCount());
    byte[] bytes = readBounded(upload.content(), upload.size(), policy.maxFileSize());
    UUID fileId = uuids.generate();
    String key = fileId + "/" + safeName(upload.originalName());
    StoredObject object = storage.put(key, new ByteArrayInputStream(bytes), bytes.length);
    Instant now = clock.now();
    try {
      return transactions.persistUpload(
          fileId,
          uuids.generate(),
          upload,
          policy,
          bytes,
          sha256(bytes),
          storage.provider(),
          object,
          actor.actorId(),
          now,
          metadata(upload.metadata()));
    } catch (RuntimeException ex) {
      storage.delete(key);
      throw ex;
    }
  }

  public InputStream download(UUID fileId) {
    ActorContext actor = actors.requireActor();
    String storageKey = transactions.authorizeDownload(fileId, actor);
    return storage.get(storageKey);
  }

  public FileRef recordScan(UUID fileId, FileScanStatus result) {
    return transactions.recordScan(fileId, result);
  }

  private static void validate(FileUpload u, FilePolicy p) {
    if (u.content() == null
        || u.ownerType() == null
        || u.ownerId() == null
        || u.fieldKey() == null
        || u.fieldKey().isBlank())
      throw new IllegalArgumentException("File owner, field, and content are required");
    if (!p.allowedMimeTypes().contains(u.mimeType()))
      throw new IllegalArgumentException("MIME type is not allowed");
    if (u.size() < 0 || u.size() > p.maxFileSize())
      throw new IllegalArgumentException("File size limit exceeded");
  }

  private static byte[] readBounded(InputStream content, long declared, long maximum) {
    try {
      byte[] value = content.readNBytes(Math.toIntExact(Math.min(maximum + 1, Integer.MAX_VALUE)));
      if (value.length != declared || value.length > maximum || content.read() != -1)
        throw new IllegalArgumentException("Content size is invalid");
      return value;
    } catch (IOException ex) {
      throw new UncheckedIOException("Unable to read upload", ex);
    }
  }

  private static String safeName(String name) {
    if (name == null || name.isBlank())
      throw new IllegalArgumentException("originalName is required");
    return name.replace('\\', '_').replace('/', '_').trim();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static JsonNode metadata(JsonNode value) {
    JsonNode result = value == null ? JsonNodeFactory.instance.objectNode() : value;
    if (!result.isObject()) throw new IllegalArgumentException("metadata must be an object");
    return result;
  }
}
