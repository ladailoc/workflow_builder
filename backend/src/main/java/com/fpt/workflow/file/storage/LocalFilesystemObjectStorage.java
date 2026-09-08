package com.fpt.workflow.file.storage;

import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class LocalFilesystemObjectStorage implements ObjectStorage {
  private final Path root;

  public LocalFilesystemObjectStorage(
      @Value("${platform.files.local-root:${java.io.tmpdir}/workflow-platform-files}")
          String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  @Override
  public StoredObject put(String storageKey, InputStream content, long size) {
    Path target = resolve(storageKey);
    try {
      Files.createDirectories(target.getParent());
      try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
        long written = content.transferTo(output);
        if (written != size) {
          Files.deleteIfExists(target);
          throw new IllegalArgumentException("Content size does not match declared size");
        }
      }
      return new StoredObject(storageKey, null);
    } catch (IOException ex) {
      throw new UncheckedIOException("Unable to store object", ex);
    }
  }

  @Override
  public InputStream get(String storageKey) {
    try {
      return Files.newInputStream(resolve(storageKey), StandardOpenOption.READ);
    } catch (IOException ex) {
      throw new UncheckedIOException("Unable to read object", ex);
    }
  }

  @Override
  public void delete(String storageKey) {
    try {
      Files.deleteIfExists(resolve(storageKey));
    } catch (IOException ex) {
      throw new UncheckedIOException("Unable to delete object", ex);
    }
  }

  @Override
  public String provider() {
    return "LOCAL_FILESYSTEM";
  }

  private Path resolve(String storageKey) {
    if (storageKey == null || storageKey.isBlank())
      throw new IllegalArgumentException("storageKey is required");
    Path resolved = root.resolve(storageKey).normalize();
    if (!resolved.startsWith(root))
      throw new IllegalArgumentException("storageKey escapes storage root");
    return resolved;
  }
}
