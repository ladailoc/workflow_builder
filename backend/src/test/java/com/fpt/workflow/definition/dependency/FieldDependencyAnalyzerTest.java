package com.fpt.workflow.definition.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FieldDependencyAnalyzerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final FieldDependencyAnalyzer analyzer = new FieldDependencyAnalyzer();
  private final UUID versionId = UUID.randomUUID();

  @Test
  void renameReportsExactInputConditionAndParticipantReferences() throws Exception {
    DependencyReport report =
        analyzer.analyze(
            FieldChange.rename(versionId, "amount", "totalAmount"),
            List.of(
                resource(
                    DependencyResourceType.NODE,
                    "/config/inputBinding",
                    "{\"amount\":\"${form.amount}\",\"ignored\":\"${form.amountExtra}\"}"),
                resource(
                    DependencyResourceType.EDGE, "/condition", "{\"left\":\"ticket.data.amount\"}"),
                resource(
                    DependencyResourceType.NODE,
                    "/config/participantResolver",
                    "{\"subject\":\"${ticket.amount.owner}\"}")));

    assertThat(report.hasBreakingDependencies()).isTrue();
    assertThat(report.dependencies())
        .extracting(FieldDependency::usageType)
        .containsExactlyInAnyOrder(
            DependencyUsageType.INPUT_BINDING,
            DependencyUsageType.EDGE_CONDITION,
            DependencyUsageType.PARTICIPANT_RESOLVER);
    assertThat(report.dependencies())
        .allMatch(item -> item.impact() == DependencyImpact.BREAKING_REFERENCE);
  }

  @Test
  void deleteFindsMultiResourceDependencies() throws Exception {
    DependencyReport report =
        analyzer.analyze(
            FieldChange.delete(versionId, "reviewers"),
            List.of(
                resource(
                    DependencyResourceType.NODE,
                    "/config/multiInstance/collection",
                    "{\"path\":\"form.reviewers\"}"),
                resource(
                    DependencyResourceType.NODE,
                    "/config/notification",
                    "{\"recipient\":\"${form.reviewers}\"}"),
                resource(
                    DependencyResourceType.NODE,
                    "/config/integrationMapping",
                    "{\"source\":\"ticket.reviewers\"}"),
                resource(
                    DependencyResourceType.FORM,
                    "/schema/fields/1/visibility",
                    "{\"path\":\"form.reviewers\"}"),
                resource(
                    DependencyResourceType.VARIABLE,
                    "/default/variable",
                    "{\"from\":\"ticket.data.reviewers\"}")));

    assertThat(report.dependencies())
        .extracting(FieldDependency::usageType)
        .containsExactlyInAnyOrder(
            DependencyUsageType.MULTI_INSTANCE_COLLECTION,
            DependencyUsageType.NOTIFICATION,
            DependencyUsageType.INTEGRATION_MAPPING,
            DependencyUsageType.FORM,
            DependencyUsageType.VARIABLE);
  }

  @Test
  void typeChangeRequiresEveryConsumerToBeRevalidated() throws Exception {
    DependencyReport report =
        analyzer.analyze(
            FieldChange.typeChange(
                versionId, "amount", TypeDescriptor.required(CanonicalValueType.STRING)),
            List.of(
                resource(
                    DependencyResourceType.EDGE, "/condition", "{\"reference\":\"form.amount\"}")));

    assertThat(report.dependencies())
        .singleElement()
        .satisfies(
            dependency -> {
              assertThat(dependency.impact())
                  .isEqualTo(DependencyImpact.TYPE_REVALIDATION_REQUIRED);
              assertThat(dependency.severity()).isEqualTo(DependencySeverity.ERROR);
            });
  }

  private DependencyResource resource(DependencyResourceType type, String path, String json)
      throws Exception {
    return new DependencyResource(type, UUID.randomUUID(), path, objectMapper.readTree(json));
  }
}
