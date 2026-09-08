package com.fpt.workflow.operations.outbox;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OutboxPublisher {
  private final OutboxTransactions transactions;
  private final OutboxTransport transport;

  public OutboxPublisher(OutboxTransactions transactions, OutboxTransport transport) {
    this.transactions = transactions;
    this.transport = transport;
  }

  public int publishAvailable(String worker, int batch, Duration lease, Duration retryDelay) {
    List<OutboxEvent> claimed = transactions.claim(worker, batch, lease);
    for (OutboxEvent event : claimed) {
      try {
        transport.publish(event);
        transactions.published(event.getId(), worker);
      } catch (Exception ex) {
        transactions.failed(
            event.getId(),
            worker,
            JsonNodeFactory.instance.objectNode().put("message", safe(ex)),
            retryDelay);
      }
    }
    return claimed.size();
  }

  private static String safe(Exception ex) {
    String m = ex.getMessage();
    return m == null ? ex.getClass().getSimpleName() : m.substring(0, Math.min(m.length(), 1000));
  }
}
