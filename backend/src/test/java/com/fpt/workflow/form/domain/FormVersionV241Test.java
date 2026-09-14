package com.fpt.workflow.form.domain;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class FormVersionV241Test {
  @Test void publishedVersionIsImmutable(){UUID actor=UUID.randomUUID();FormVersion version=FormVersion.draft(UUID.randomUUID(),UUID.randomUUID(),1,actor,Instant.now(),JsonNodeFactory.instance.objectNode().put("value",1));version.publish("sum",JsonNodeFactory.instance.objectNode().put("value",1),actor,Instant.now());assertThatThrownBy(()->version.update(0,JsonNodeFactory.instance.objectNode())).isInstanceOf(IllegalStateException.class).hasMessageContaining("immutable");}
}
