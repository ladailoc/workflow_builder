package com.fpt.workflow.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.fpt.workflow.security.masking.DefaultSensitiveValueMasker;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import com.fpt.workflow.testing.FixedPlatformClock;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

class SharedConventionsTest {

  @Test
  void providesInjectableClockUuidAndMaskingAbstractions() {
    Instant now = Instant.parse("2026-09-07T03:00:00Z");
    UuidGenerator uuidGenerator = new Identifiers();

    assertThat(new FixedPlatformClock(now).now()).isEqualTo(now);
    assertThat(uuidGenerator.generate().version()).isEqualTo(4);
    assertThat(new DefaultSensitiveValueMasker().mask("secret"))
        .isEqualTo(DefaultSensitiveValueMasker.REDACTED);
  }

  @Test
  void commandTransactionsRollbackOnCheckedExceptionsAndHaveABoundedTimeout()
      throws NoSuchMethodException {
    Transactional transaction = transactionOn("command");

    assertThat(transaction.readOnly()).isFalse();
    assertThat(transaction.rollbackFor()).containsExactly(Exception.class);
    assertThat(transaction.timeoutString())
        .isEqualTo("${platform.transaction.command-timeout-seconds:10}");
  }

  @Test
  void queryTransactionsAreReadOnly() throws NoSuchMethodException {
    assertThat(transactionOn("query").readOnly()).isTrue();
  }

  private static Transactional transactionOn(String methodName) throws NoSuchMethodException {
    Method method = TransactionFixture.class.getDeclaredMethod(methodName);
    return AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
  }

  private static final class TransactionFixture {

    @TransactionalCommand
    void command() {}

    @TransactionalQuery
    void query() {}
  }
}
