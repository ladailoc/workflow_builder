package com.fpt.workflow.slanotification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.slanotification.domain.NotificationDispatch;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Sends email through the configured provider webhook and keeps delivery retryable. */
@Component
public final class EmailNotificationChannel implements NotificationChannel {
  private final EmployeeRepository employees;
  private final ObjectMapper mapper;
  private final HttpClient client = HttpClient.newHttpClient();
  private final String webhookUrl;

  public EmailNotificationChannel(
      EmployeeRepository employees,
      ObjectMapper mapper,
      @Value("${platform.notifications.email.webhook-url:}") String webhookUrl) {
    this.employees = employees;
    this.mapper = mapper;
    this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
  }

  @Override
  public String channel() {
    return "EMAIL";
  }

  @Override
  public void send(NotificationDispatch dispatch) {
    if (webhookUrl.isBlank()) {
      throw new IllegalStateException("EMAIL_CHANNEL_NOT_CONFIGURED");
    }
    String recipient =
        employees
            .findByUserId(dispatch.getRecipientUserId())
            .map(com.fpt.workflow.organization.domain.Employee::getEmail)
            .orElseThrow(() -> new IllegalStateException("EMAIL_RECIPIENT_NOT_FOUND"));
    ObjectNode body = mapper.createObjectNode();
    body.put("to", recipient);
    body.put("subject", dispatch.getTemplateSnapshotJson().path("subject").asText("Workflow notification"));
    body.put("text", dispatch.getTemplateSnapshotJson().path("body").asText(dispatch.getPayloadJson().toString()));
    body.set("payload", dispatch.getPayloadJson());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    try {
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("EMAIL_PROVIDER_HTTP_" + response.statusCode());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("EMAIL_PROVIDER_INTERRUPTED", interrupted);
    } catch (java.io.IOException ioFailure) {
      throw new IllegalStateException("EMAIL_PROVIDER_UNAVAILABLE", ioFailure);
    }
  }
}
