package com.fpt.workflow.nodetype;


/**
 * Deterministic, side-effect-free migration of one node config schema version to the next. A
 * migrator never mutates persisted published snapshots: it only upgrades draft configuration
 * when an explicit upgrade is requested (P2-07).
 */
public interface ConfigSchemaMigrator {

  /** Target schema version produced by this migrator. */
  int toVersion();

  /**
   * Converts a config valid under the source version into an equivalent config valid under
   * {@link #toVersion()}. Implementations must be pure: same input → same output, no I/O.
   */
  com.fasterxml.jackson.databind.node.ObjectNode migrate(com.fasterxml.jackson.databind.node.ObjectNode config);

  static ConfigSchemaMigrator identityTo(int targetVersion) {
    return new ConfigSchemaMigrator() {
      @Override
      public int toVersion() {
        return targetVersion;
      }

      @Override
      public com.fasterxml.jackson.databind.node.ObjectNode migrate(
          com.fasterxml.jackson.databind.node.ObjectNode config) {
        return config.deepCopy();
      }
    };
  }
}
