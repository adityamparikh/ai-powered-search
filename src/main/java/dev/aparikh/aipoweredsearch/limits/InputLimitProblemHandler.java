package dev.aparikh.aipoweredsearch.limits;

import jakarta.validation.ConstraintViolation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders Spring MVC's standard exceptions as RFC 9457 problem details, and request validation
 * failures as a 400 that names each violated {@link InputLimit}.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives every standard MVC error (a missing
 * parameter, {@code k=abc}, an unsupported API version) a problem-details body instead of an empty
 * one; the status codes are unchanged.
 *
 * <p>A {@code @RequestParam} constraint fails through Spring MVC's method validation ({@link
 * HandlerMethodValidationException}); a {@code @Valid} request body fails with {@link
 * MethodArgumentNotValidException}. Both are reported the same way: one entry per violation under
 * {@code errors}, and, when the violation is an {@link InputLimit}, the property that sets it and
 * its current maximum. The {@code type} member is omitted, which RFC 9457 reads as {@code
 * about:blank}.
 *
 * <pre>{@code
 * {
 *   "title": "Input limit exceeded",
 *   "status": 400,
 *   "detail": "query must be at most 1000 characters (search.limits.max-query-length)",
 *   "instance": "/api/v1/search/books",
 *   "errors": [
 *     {"field": "query", "message": "must be at most 1000 characters",
 *      "limit": "search.limits.max-query-length", "max": 1000}
 *   ]
 * }
 * }</pre>
 *
 * <p>It also registers {@link InputLimits}, so that the limits are bound wherever controllers are,
 * including {@code @WebMvcTest} slices.
 */
@RestControllerAdvice
@EnableConfigurationProperties(InputLimits.class)
class InputLimitProblemHandler extends ResponseEntityExceptionHandler {

  private final InputLimits limits;

  InputLimitProblemHandler(InputLimits limits) {
    this.limits = limits;
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (ex.isForReturnValue()) {
      // A constraint on what the controller returned is a server bug, not bad input (500).
      return super.handleHandlerMethodValidationException(ex, headers, status, request);
    }
    List<Violation> violations = new ArrayList<>();
    for (ParameterValidationResult result : ex.getParameterValidationResults()) {
      if (result instanceof ParameterErrors errors) {
        errors.getAllErrors().forEach(error -> violations.add(fromObjectError(error)));
        continue;
      }
      String name = parameterName(result.getMethodParameter());
      for (MessageSourceResolvable error : result.getResolvableErrors()) {
        violations.add(new Violation(name, message(error), limitOf(unwrap(result, error))));
      }
    }
    return handleExceptionInternal(
        ex, problem(violations), headers, HttpStatus.BAD_REQUEST, request);
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Violation> violations =
        ex.getBindingResult().getAllErrors().stream().map(this::fromObjectError).toList();
    return handleExceptionInternal(
        ex, problem(violations), headers, HttpStatus.BAD_REQUEST, request);
  }

  /** A field error is named by its path; a global (class-level) one by the object's name. */
  private Violation fromObjectError(ObjectError error) {
    ConstraintViolation<?> violation =
        error.contains(ConstraintViolation.class) ? error.unwrap(ConstraintViolation.class) : null;
    String field =
        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
    return new Violation(field, message(error), limitOf(violation));
  }

  private ProblemDetail problem(List<Violation> violations) {
    boolean allLimits =
        !violations.isEmpty() && violations.stream().allMatch(v -> v.limit() != null);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            violations.stream().map(Violation::describe).collect(Collectors.joining("; ")));
    problem.setTitle(allLimits ? "Input limit exceeded" : "Invalid request");
    problem.setProperty("errors", violations.stream().map(this::toMap).toList());
    return problem;
  }

  private Map<String, Object> toMap(Violation violation) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("field", violation.field());
    entry.put("message", violation.message());
    InputLimit limit = violation.limit();
    if (limit != null) {
      entry.put("limit", limit.property());
      entry.put("max", limit.max(limits));
    }
    return entry;
  }

  private static String parameterName(MethodParameter parameter) {
    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
    if (requestParam != null && !requestParam.name().isEmpty()) {
      return requestParam.name();
    }
    String name = parameter.getParameterName();
    return name != null ? name : "arg" + parameter.getParameterIndex();
  }

  private static String message(MessageSourceResolvable error) {
    String message = error.getDefaultMessage();
    return message != null ? message : "is invalid";
  }

  private static @Nullable ConstraintViolation<?> unwrap(
      ParameterValidationResult result, MessageSourceResolvable error) {
    try {
      return result.unwrap(error, ConstraintViolation.class);
    } catch (IllegalArgumentException notAViolation) {
      return null;
    }
  }

  private static @Nullable InputLimit limitOf(@Nullable ConstraintViolation<?> violation) {
    if (violation != null
        && violation.getConstraintDescriptor().getAnnotation() instanceof WithinLimit withinLimit) {
      return withinLimit.value();
    }
    return null;
  }

  private record Violation(String field, String message, @Nullable InputLimit limit) {
    String describe() {
      String text = field + " " + message;
      return limit != null ? text + " (" + limit.property() + ")" : text;
    }
  }
}
