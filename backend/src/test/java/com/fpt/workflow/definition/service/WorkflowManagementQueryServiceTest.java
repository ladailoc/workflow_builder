package com.fpt.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import com.fpt.workflow.shared.domain.page.PageRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class WorkflowManagementQueryServiceTest {

  private WorkflowDefinitionRepository definitionRepository;
  private WorkflowVersionRepository versionRepository;
  private NodeDefinitionRepository nodeRepository;
  private EdgeDefinitionRepository edgeRepository;
  private WorkflowFormRepository formRepository;
  private RequestTypeRepository requestTypeRepository;
  private WorkflowManagementQueryService queryService;

  @BeforeEach
  void setUp() {
    definitionRepository = mock(WorkflowDefinitionRepository.class);
    versionRepository = mock(WorkflowVersionRepository.class);
    nodeRepository = mock(NodeDefinitionRepository.class);
    edgeRepository = mock(EdgeDefinitionRepository.class);
    formRepository = mock(WorkflowFormRepository.class);
    requestTypeRepository = mock(RequestTypeRepository.class);

    queryService =
        new WorkflowManagementQueryService(
            definitionRepository,
            versionRepository,
            nodeRepository,
            edgeRepository,
            formRepository,
            requestTypeRepository);
  }

  @Test
  void workflows_whenQueryAndLifecycleNull_callsFindAll() {
    when(definitionRepository.findAll(any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));

    var result = queryService.workflows(null, null, PageRequest.of(0, 10));
    assertThat(result.items()).isEmpty();
    verify(definitionRepository).findAll(any(Pageable.class));
  }

  @Test
  void workflows_whenQueryNullAndLifecycleProvided_callsFindAllByLifecycle() {
    when(definitionRepository.findAllByLifecycle(
            eq(WorkflowDefinitionLifecycle.ACTIVE), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));

    var result =
        queryService.workflows(null, WorkflowDefinitionLifecycle.ACTIVE, PageRequest.of(0, 10));
    assertThat(result.items()).isEmpty();
    verify(definitionRepository)
        .findAllByLifecycle(eq(WorkflowDefinitionLifecycle.ACTIVE), any(Pageable.class));
  }

  @Test
  void workflows_whenQueryProvidedAndLifecycleNull_callsSearchByPattern() {
    when(definitionRepository.searchByPattern(eq("%purchase%"), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));

    var result = queryService.workflows("  Purchase  ", null, PageRequest.of(0, 10));
    assertThat(result.items()).isEmpty();
    verify(definitionRepository).searchByPattern(eq("%purchase%"), any(Pageable.class));
  }

  @Test
  void workflows_whenQueryAndLifecycleProvided_callsSearchByPatternAndLifecycle() {
    when(definitionRepository.searchByPatternAndLifecycle(
            eq("%purchase%"), eq(WorkflowDefinitionLifecycle.ACTIVE), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));

    var result =
        queryService.workflows(
            "Purchase", WorkflowDefinitionLifecycle.ACTIVE, PageRequest.of(0, 10));
    assertThat(result.items()).isEmpty();
    verify(definitionRepository)
        .searchByPatternAndLifecycle(
            eq("%purchase%"), eq(WorkflowDefinitionLifecycle.ACTIVE), any(Pageable.class));
  }

  @Test
  void requestTypes_whenQueryAndActiveNull_callsFindAll() {
    when(requestTypeRepository.findAll(any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));

    var result = queryService.requestTypes(null, null, PageRequest.of(0, 10));
    assertThat(result.items()).isEmpty();
    verify(requestTypeRepository).findAll(any(Pageable.class));
  }

  @Test
  void requestTypes_whenQueryNullAndActiveProvided_callsFindAllByActive() {
    when(requestTypeRepository.findAllByActive(eq(true), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));

    var result = queryService.requestTypes(null, true, PageRequest.of(0, 10));
    assertThat(result.items()).isEmpty();
    verify(requestTypeRepository).findAllByActive(eq(true), any(Pageable.class));
  }

  @Test
  void requestTypes_whenQueryProvidedAndActiveNull_callsSearchByPattern() {
    when(requestTypeRepository.searchByPattern(eq("%leave%"), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));

    var result = queryService.requestTypes("Leave", null, PageRequest.of(0, 10));
    assertThat(result.items()).isEmpty();
    verify(requestTypeRepository).searchByPattern(eq("%leave%"), any(Pageable.class));
  }

  @Test
  void requestTypes_whenQueryAndActiveProvided_callsSearchByPatternAndActive() {
    when(requestTypeRepository.searchByPatternAndActive(
            eq("%leave%"), eq(true), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));

    var result = queryService.requestTypes("Leave", true, PageRequest.of(0, 10));
    assertThat(result.items()).isEmpty();
    verify(requestTypeRepository)
        .searchByPatternAndActive(eq("%leave%"), eq(true), any(Pageable.class));
  }
}
