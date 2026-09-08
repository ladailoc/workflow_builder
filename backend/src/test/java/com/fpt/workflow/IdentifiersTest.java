package com.fpt.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fpt.workflow.shared.Identifiers;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

  @Test
  void createsRfc4122RandomUuidIdentifiers() {
    var first = Identifiers.newUuid();
    var second = Identifiers.newUuid();

    assertThat(first.version()).isEqualTo(4);
    assertThat(first.variant()).isEqualTo(2);
    assertThat(first).isNotEqualTo(second);
  }
}
