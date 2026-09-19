package com.fme.tinylink.exception;

import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice 
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 1. Target resource not found -> 404
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
    
    // 2. Request validation failed -> 400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, 
            "Validation failed for one or more fields"
        );
        problem.setTitle("Bad Request");
        // Collect field-level details
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(err -> Map.of("field", err.getField(), "message", err.getDefaultMessage()))
            .toList();
        problem.setProperty("invalid_params", fieldErrors);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // 3. Database conflict (e.g., duplicate shortCode) -> 409
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleConflict(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Resource conflict or duplicate entry."
        );
        problem.setTitle("Conflict");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // 4. Catch-all for unexpected bugs / runtime exceptions -> 500
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnhandledException(Exception ex) {
        String errorId = UUID.randomUUID().toString();
        // CRITICAL: Log full stack trace with the errorId for internal debugging
        log.error("Internal server error [Error ID: {}]", errorId, ex);
        // Client receives a safe, sanitized message referencing the error ID
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please contact support with the error ID."
        );
        problem.setTitle("Internal Server Error");
        problem.setProperty("error_id", errorId);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

}
