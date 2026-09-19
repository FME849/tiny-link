---
name: spring-boot-exception-handling
description: >-
  Skill for designing, implementing, and auditing production-grade exception handling in Spring Boot applications using RFC 7807 / RFC 9457 (ProblemDetail). Use this skill when building or refactoring Global Exception Handlers (@RestControllerAdvice), mapping business exceptions to correct HTTP status codes (4xx vs 5xx), handling validation errors, preventing information leakage, and ensuring observability with trace/correlation IDs.
---

# Production-Grade Spring Boot Exception Handling Skill

This skill provides the end-to-end framework, design principles, and concrete code templates for implementing production-grade HTTP exception handling in Spring Boot (3.x and 4.x) applications.

---

## 1. Core Architectural Principles

```text
               ┌────────────────────────────────────────────────────────┐
               │                  Application Layer                     │
               │  Throws pure Domain Exceptions (Clean Java POJOs)      │
               │  e.g., ResourceNotFoundException, DuplicateCodeException│
               └──────────────────────────┬─────────────────────────────┘
                                          │
                                          ▼
               ┌────────────────────────────────────────────────────────┐
               │             @RestControllerAdvice                      │
               │  Translates exceptions into standardized HTTP responses│
               └──────────┬──────────────────────────────────┬──────────┘
                          │                                  │
                          ▼ (4xx: Client Error)              ▼ (5xx: Server Error)
               ┌───────────────────────────┐      ┌───────────────────────────┐
               │  - No stack trace logged  │      │  - Generate Trace/UUID    │
               │  - Actionable feedback    │      │  - Log full stack trace   │
               │  - Field-level validation │      │  - Sanitize client message│
               └───────────────────────────┘      └───────────────────────────┘
```

### The Golden Rules
1. **Decouple Domain from HTTP:** Do not put `@ResponseStatus` on business exceptions. Exceptions thrown by services should be clean Java runtime exceptions. The HTTP translation layer (`@RestControllerAdvice`) owns status code mapping.
2. **Never Catch-All to 4xx:** Never map generic `RuntimeException` or `Exception` to `404 Not Found` or `400 Bad Request`. Unhandled exceptions represent server bugs or infrastructure outages and **must** return `500 Internal Server Error` to keep monitoring/alerting honest.
3. **No Information Leakage:** Never expose SQL queries, class names, internal IPs, or stack traces in `5xx` responses. Return a generic message with a unique `error_id` / `trace_id`.
4. **Log Full Context Server-Side:** Every `5xx` error must log the full stack trace along with the `error_id` so on-call engineers can triage immediately in logging systems (ELK, Datadog, CloudWatch).
5. **Standardized RFC 7807 Payload:** Use Spring Boot's native `ProblemDetail` (RFC 7807 / RFC 9457) for consistent error contracts across all microservices.

---

## 2. HTTP Status Code Decision Matrix

| Exception Type | Target Status | Reason | Logging Level |
| :--- | :--- | :--- | :--- |
| `ResourceNotFoundException`, `NoResourceFoundException` | **404 Not Found** | Resource identified by ID/key does not exist | `DEBUG` / `INFO` |
| `MethodArgumentNotValidException`, `HandlerMethodValidationException` | **400 Bad Request** | Request body/param failed validation constraints | `DEBUG` / `WARN` |
| `HttpMessageNotReadableException` | **400 Bad Request** | Malformed JSON syntax or unparseable enum/date | `DEBUG` / `WARN` |
| `DataIntegrityViolationException` | **409 Conflict** | Database unique constraint or duplicate key violation | `WARN` |
| `AccessDeniedException` | **403 Forbidden** | Authenticated user lacks permission for resource | `WARN` |
| `AuthenticationException` | **401 Unauthorized** | Missing, invalid, or expired credentials | `WARN` |
| `DataAccessResourceFailureException`, Redis offline | **503 Service Unavailable** | External database or cache unreachable | `ERROR` (with stack trace) |
| Generic `RuntimeException`, `Exception` (NPE, etc.) | **500 Internal Server Error** | Unexpected server-side bug | `ERROR` (with stack trace + UUID) |

---

## 3. RFC 7807 ProblemDetail Structure

Spring Boot provides `org.springframework.http.ProblemDetail`:

```json
{
  "type": "urn:tinylink:errors:validation-error",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed for 1 field",
  "instance": "/api/v1/url",
  "timestamp": "2026-09-19T07:45:00Z",
  "invalid_params": [
    {
      "field": "longUrl",
      "message": "Must be a valid HTTPS URL"
    }
  ]
}
```

### Problem `type` Guidelines
- **Omit / Default (`about:blank`):** Recommended if there is no public documentation portal.
- **URN (`urn:<app>:errors:<code-name>`):** Standard, location-independent, requires no host domain.
- **Dynamic URI (`ServletUriComponentsBuilder`):** Use only if your app actually serves error documentation endpoints.

---

## 4. Production Code Template

### Step 1: Clean Domain Exception
```java
package com.fme.tinylink.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
    
    public ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s not found with %s: '%s'", resourceName, fieldName, fieldValue));
    }
}
```

### Step 2: Production `@RestControllerAdvice`
```java
package com.fme.tinylink.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 1. Target Resource Not Found -> 404
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        log.info("Resource not found: {} at path: {}", ex.getMessage(), request.getRequestURI());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        problem.setType(URI.create("urn:tinylink:errors:not-found"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // 2. Route / Static Asset Not Found -> 404
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The requested endpoint does not exist");
        problem.setTitle("Endpoint Not Found");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // 3. Request Body Validation Failed -> 400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Map<String, String>> invalidParams = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> Map.of(
                        "field", err.getField(),
                        "message", err.getDefaultMessage() != null ? err.getDefaultMessage() : "Invalid value"
                ))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                String.format("Validation failed for %d field(s)", invalidParams.size())
        );
        problem.setTitle("Validation Error");
        problem.setType(URI.create("urn:tinylink:errors:validation-error"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("invalid_params", invalidParams);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // 4. Malformed JSON or Unparseable Payload -> 400
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Malformed JSON payload: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Malformed JSON request body or incompatible data types"
        );
        problem.setTitle("Malformed Request Body");
        problem.setType(URI.create("urn:tinylink:errors:malformed-body"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // 5. Database Conflict / Unique Constraint Violation -> 409
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Database conflict at path {}: {}", request.getRequestURI(), ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "A resource conflict occurred (e.g., duplicate unique key)."
        );
        problem.setTitle("Conflict");
        problem.setType(URI.create("urn:tinylink:errors:conflict"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // 6. Catch-All for Unexpected Bugs -> 500 (Sanitized & Traceable)
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnhandledException(Exception ex, HttpServletRequest request) {
        String errorId = UUID.randomUUID().toString();

        // CRITICAL: Log full stack trace with correlation errorId
        log.error("Internal server error [Error ID: {}] at path: {}", errorId, request.getRequestURI(), ex);

        // Client receives clean, non-leaking message with errorId for reference
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected internal error occurred. Quote the error ID when contacting support."
        );
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("urn:tinylink:errors:internal-server-error"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("error_id", errorId);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
```

---

## 5. Audit Checklist for Code Reviews

When reviewing an existing or newly created Spring Boot controller/exception handling layer, verify:

- [ ] Are DTOs/records decorated with validation annotations (`@NotBlank`, `@URL`, `@Size`, etc.)?
- [ ] Are controller `@RequestBody` parameters annotated with `@Valid`?
- [ ] Is there any `@ResponseStatus` directly placed on domain exceptions that prevents custom formatting?
- [ ] Are client errors (400, 404, 409) separated from server errors (500, 503)?
- [ ] Does the `500` handler generate a UUID/trace ID and log the complete stack trace?
- [ ] Is the `500` client response sanitized (no internal database table names or stack traces)?
- [ ] Are field-level validation errors structured as an array/list of errors with field names?
