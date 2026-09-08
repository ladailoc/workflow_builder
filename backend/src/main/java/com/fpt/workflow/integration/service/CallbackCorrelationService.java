package com.fpt.workflow.integration.service;

public interface CallbackCorrelationService {

  String generateCorrelationId();

  CallbackProcessingResult processCallback(CallbackCommand command);
}
