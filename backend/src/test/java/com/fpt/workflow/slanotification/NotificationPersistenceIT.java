package com.fpt.workflow.slanotification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.slanotification.domain.NotificationDispatchStatus;
import com.fpt.workflow.slanotification.repository.NotificationDispatchRepository;
import com.fpt.workflow.slanotification.service.NotificationTransactions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationPersistenceIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired NotificationTransactions transactions;
  @Autowired NotificationDispatchRepository dispatches;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @Test
  void duplicateLogicalDispatchReturnsOriginalAndTerminalEventCancelsLateJob() {
    UUID eventId = createEvent();
    String dedup = "notification:" + UUID.randomUUID();
    var recipient = mapper.createObjectNode().put("resolvedUserId", UUID.randomUUID().toString());
    var template = mapper.createObjectNode().put("templateKey", "PURCHASE_APPROVED");
    var payload = mapper.createObjectNode().put("orderId", "PO-1");
    var first =
        transactions.create(
            eventId,
            null,
            null,
            "EMAIL",
            UUID.randomUUID(),
            recipient,
            template,
            payload,
            dedup,
            3,
            false);
    var duplicate =
        transactions.create(
            eventId,
            null,
            null,
            "EMAIL",
            UUID.randomUUID(),
            recipient,
            template,
            payload,
            dedup,
            3,
            false);
    assertThat(duplicate.getId()).isEqualTo(first.getId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_dispatches WHERE dedup_key=?",
                Long.class,
                dedup))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM workflow_jobs WHERE dedup_key=?",
                Long.class,
                "notification-job:" + first.getId()))
        .isEqualTo(1L);

    jdbc.update(
        "UPDATE events SET status='COMPLETED',outcome='DONE',ended_at=now() WHERE id=?", eventId);
    assertThat(transactions.begin(first.getId())).isEmpty();
    assertThat(dispatches.findById(first.getId()).orElseThrow().getStatus())
        .isEqualTo(NotificationDispatchStatus.CANCELLED);
  }

  private UUID createEvent() {
    UUID definition = UUID.randomUUID(),
        version = UUID.randomUUID(),
        requestType = UUID.randomUUID(),
        ticket = UUID.randomUUID(),
        revision = UUID.randomUUID(),
        event = UUID.randomUUID(),
        actor = UUID.randomUUID();
    String suffix = event.toString();
    jdbc.update(
        "INSERT INTO workflow_definitions(id,key,name,lifecycle,owner_id,created_by,created_at,updated_at) VALUES (?,?,'Notification Test','ACTIVE',?,?,now(),now())",
        definition,
        "notify-" + suffix,
        actor,
        actor);
    jdbc.update(
        "INSERT INTO workflow_versions(id,definition_id,version_no,status,revision,created_by,created_at) VALUES (?,?,1,'DRAFT',0,?,now())",
        version,
        definition,
        actor);
    jdbc.update(
        "UPDATE workflow_versions SET status='PUBLISHED',checksum='notification-test',execution_package_json='{}'::jsonb,published_by=?,published_at=now() WHERE id=?",
        actor,
        version);
    jdbc.update(
        "UPDATE workflow_definitions SET current_published_version_id=? WHERE id=?",
        version,
        definition);
    jdbc.update(
        "INSERT INTO request_types(id,key,name,category,workflow_definition_id,active,creation_policy_json,created_at,updated_at) VALUES (?,?,'Notification','GENERAL',?,true,'{}'::jsonb,now(),now())",
        requestType,
        "notify-req-" + suffix,
        definition);
    jdbc.update(
        "INSERT INTO tickets(id,request_type_id,creator_id,status,data_json,created_at,updated_at) VALUES (?,?,?,'DRAFT','{}'::jsonb,now(),now())",
        ticket,
        requestType,
        actor);
    jdbc.update(
        "INSERT INTO ticket_revisions(id,ticket_id,revision_no,data_snapshot_json,source_schema_version,schema_checksum,submitted_by,submitted_at) VALUES (?,?,1,'{}'::jsonb,'v1','sum',?,now())",
        revision,
        ticket,
        actor);
    jdbc.update(
        "UPDATE tickets SET status='SUBMITTED',data_revision=1,current_revision_id=?,submitted_at=now() WHERE id=?",
        revision,
        ticket);
    jdbc.update(
        "INSERT INTO events(id,ticket_id,workflow_version_id,started_ticket_revision_id,event_type,status,root_event_id,trigger_type,variables_json,started_by,started_at) VALUES (?,?,?,?,'ROOT','RUNNING',?,'USER_SUBMIT','{}'::jsonb,?,now())",
        event,
        ticket,
        version,
        revision,
        event,
        actor);
    return event;
  }
}
