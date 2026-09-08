package com.fpt.workflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class JsonSerializationRulesTest {

  @Autowired private ObjectMapper objectMapper;

  @Test
  void serializesInstantAsUtcIso8601AndOmitsNulls() throws Exception {
    String json =
        objectMapper.writeValueAsString(
            new JsonFixture(Instant.parse("2026-09-07T03:00:00Z"), null));

    assertThat(json).isEqualTo("{\"occurredAt\":\"2026-09-07T03:00:00Z\"}");
  }

  @Test
  void rejectsUnknownJsonProperties() {
    assertThatThrownBy(
            () ->
                objectMapper.readValue(
                    "{\"occurredAt\":\"2026-09-07T03:00:00Z\",\"unknown\":true}",
                    JsonFixture.class))
        .isInstanceOf(UnrecognizedPropertyException.class);
  }

  private record JsonFixture(Instant occurredAt, String optional) {}
}
