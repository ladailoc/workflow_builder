package com.fpt.workflow.shared.api;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public final class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final ApiProblemFactory problemFactory;

  public ApiExceptionHandler(ApiProblemFactory problemFactory) {
    this.problemFactory = problemFactory;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiProblem> handleApiException(
      ApiException exception, HttpServletRequest request) {
    return problem(
        exception.status(), exception.code(), exception.getMessage(), List.of(), request);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMediaTypeNotSupportedException.class
  })
  ResponseEntity<ApiProblem> handleBadRequest(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "BAD_REQUEST",
        "The request syntax, type, or media type is invalid.",
        List.of(),
        request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiProblem> handleForbidden(
      AccessDeniedException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.FORBIDDEN,
        "FORBIDDEN",
        "The authenticated actor is not allowed to perform this operation.",
        List.of(),
        request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiProblem> handleNoResource(
      NoResourceFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        "The requested resource was not found.",
        List.of(),
        request);
  }

  @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
  ResponseEntity<ApiProblem> handleOptimisticLock(
      RuntimeException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "OPTIMISTIC_LOCK_CONFLICT",
        "The resource was modified by another request. Reload it and retry the command.",
        List.of(),
        request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiProblem> handleBodyValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<ValidationError> errors =
        exception.getBindingResult().getAllErrors().stream()
            .map(ApiExceptionHandler::toValidationError)
            .sorted(Comparator.comparing(ValidationError::field))
            .toList();
    return validationProblem(errors, request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiProblem> handleConstraintValidation(
      ConstraintViolationException exception, HttpServletRequest request) {
    List<ValidationError> errors =
        exception.getConstraintViolations().stream()
            .map(
                violation ->
                    new ValidationError(
                        violation.getPropertyPath().toString(),
                        violation
                            .getConstraintDescriptor()
                            .getAnnotation()
                            .annotationType()
                            .getSimpleName(),
                        violation.getMessage()))
            .sorted(Comparator.comparing(ValidationError::field))
            .toList();
    return validationProblem(errors, request);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ApiProblem> handleMethodValidation(
      HandlerMethodValidationException exception, HttpServletRequest request) {
    List<ValidationError> errors =
        exception.getAllErrors().stream()
            .map(ApiExceptionHandler::toValidationError)
            .sorted(Comparator.comparing(ValidationError::field))
            .toList();
    return validationProblem(errors, request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiProblem> handleUnexpected(Exception exception, HttpServletRequest request) {
    LOGGER.error(
        "Unhandled API exception correlationId={} requestId={}",
        RequestCorrelationFilter.correlationId(request),
        RequestCorrelationFilter.requestId(request),
        exception);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "An unexpected error occurred.",
        List.of(),
        request);
  }

  private ResponseEntity<ApiProblem> validationProblem(
      List<ValidationError> errors, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "VALIDATION_FAILED",
        "The request is syntactically valid but violates validation rules.",
        errors,
        request);
  }

  private ResponseEntity<ApiProblem> problem(
      HttpStatus status,
      String code,
      String detail,
      List<ValidationError> errors,
      HttpServletRequest request) {
    ApiProblem body = problemFactory.create(status, code, detail, errors, request);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  private static ValidationError toValidationError(MessageSourceResolvable error) {
    String field =
        error instanceof FieldError fieldError
            ? fieldError.getField()
            : error instanceof ObjectError objectError ? objectError.getObjectName() : "request";
    String[] codes = error.getCodes();
    String code =
        error instanceof ObjectError objectError && objectError.getCode() != null
            ? objectError.getCode()
            : codes == null || codes.length == 0 ? "INVALID" : codes[0];
    String message =
        error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage();
    return new ValidationError(field, code, message);
  }
}
