package com.persistent.auditlog.api;

import com.persistent.auditlog.service.AuditEventFailureLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Shared across all /audit/** controllers so new endpoints (redaction,
 * retention, export) get the same validation-error handling as the original
 * AuditEventController without duplicating it per controller.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AuditEventFailureLogger failureLogger;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidationException(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getAllErrors().stream()
            .map(error -> error.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");

        failureLogger.logValidationFailure("VALIDATION_ERROR", errorMessage, null);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Validation error: " + errorMessage);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid request: " + ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleAccessDeniedException(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body("Forbidden: the authenticated API key's role does not permit this operation");
    }
}
