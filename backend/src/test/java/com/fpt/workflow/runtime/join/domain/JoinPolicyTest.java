package com.fpt.workflow.runtime.join.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JoinPolicyTest {

  @ParameterizedTest
  @ValueSource(strings = {"ANY", "any", "Any", " aNy ", "XOR", "xor", "FIRST", "first"})
  @DisplayName("ANY, XOR, and FIRST resolve to FIRST join policy")
  void anyAndXorResolveToFirst(String val) {
    assertThat(JoinPolicy.fromString(val)).isEqualTo(JoinPolicy.FIRST);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ALL", "all", "All", "AND", "and", "And", "   "})
  @DisplayName("ALL, AND, and blank resolve to AND join policy")
  void allAndBlankResolveToAnd(String val) {
    assertThat(JoinPolicy.fromString(val)).isEqualTo(JoinPolicy.AND);
  }

  @Test
  @DisplayName("Null resolves to default AND policy")
  void nullResolvesToAnd() {
    assertThat(JoinPolicy.fromString(null)).isEqualTo(JoinPolicy.AND);
  }

  @Test
  @DisplayName("N_OF_M resolves correctly")
  void nOfMResolvesCorrectly() {
    assertThat(JoinPolicy.fromString("N_OF_M")).isEqualTo(JoinPolicy.N_OF_M);
    assertThat(JoinPolicy.fromString("n_of_m")).isEqualTo(JoinPolicy.N_OF_M);
  }

  @Test
  @DisplayName("Unrecognized values fall back to AND")
  void unrecognizedValuesFallBackToAnd() {
    assertThat(JoinPolicy.fromString("UNKNOWN_VALUE")).isEqualTo(JoinPolicy.AND);
  }
}
