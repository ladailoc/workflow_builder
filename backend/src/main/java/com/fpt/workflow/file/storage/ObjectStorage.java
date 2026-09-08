package com.fpt.workflow.file.storage;

import java.io.InputStream;

public interface ObjectStorage {
  StoredObject put(String storageKey, InputStream content, long size);

  InputStream get(String storageKey);

  void delete(String storageKey);

  String provider();
}
