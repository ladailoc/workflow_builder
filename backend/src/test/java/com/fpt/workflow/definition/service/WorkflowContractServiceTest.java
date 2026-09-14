package com.fpt.workflow.definition.service;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.*;
import com.fpt.workflow.shared.domain.value.*;
import java.time.Instant;import java.util.*;import org.junit.jupiter.api.Test;
class WorkflowContractServiceTest {
  @Test void rejectsDuplicateStatesBeforePersistence(){var versions=mock(WorkflowVersionRepository.class);var inputs=mock(WorkflowInputDefinitionRepository.class);var states=mock(WorkflowStateDefinitionRepository.class);UUID workflow=UUID.randomUUID(),versionId=UUID.randomUUID();when(versions.findById(versionId)).thenReturn(Optional.of(WorkflowVersion.createDraft(versionId,workflow,1,null,null,UUID.randomUUID(),Instant.now())));var service=new WorkflowContractService(versions,inputs,states,UUID::randomUUID);var state=new WorkflowContractService.StateCommand("review","Review",null,null,false,0,JsonNodeFactory.instance.objectNode());assertThatThrownBy(()->service.replaceStates(workflow,versionId,0,List.of(state,state))).hasMessageContaining("WORKFLOW-STATE-DUPLICATE");verifyNoInteractions(states);}
  @Test void rejectsIncompatibleInputDefault(){var versions=mock(WorkflowVersionRepository.class);var inputs=mock(WorkflowInputDefinitionRepository.class);var states=mock(WorkflowStateDefinitionRepository.class);UUID workflow=UUID.randomUUID(),versionId=UUID.randomUUID();when(versions.findById(versionId)).thenReturn(Optional.of(WorkflowVersion.createDraft(versionId,workflow,1,null,null,UUID.randomUUID(),Instant.now())));var service=new WorkflowContractService(versions,inputs,states,UUID::randomUUID);var input=new WorkflowContractService.InputCommand("amount",null,TypeDescriptor.required(CanonicalValueType.NUMBER),true,JsonNodeFactory.instance.textNode("wrong"),null,false,null,0);assertThatThrownBy(()->service.replaceInputs(workflow,versionId,0,List.of(input))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(inputs);}
}
