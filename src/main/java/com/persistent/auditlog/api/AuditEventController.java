package com.persistent.auditlog.api;

import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.service.AuditEventFailureLogger;
import com.persistent.auditlog.service.AuditEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/audit/events")
@RequiredArgsConstructor
@Tag(name = "Audit Events", description = "Append-only audit event write and query APIs")
public class AuditEventController {

    private final AuditEventService auditEventService;
    private final AuditEventFailureLogger failureLogger;

    @PostMapping
    @Operation(summary = "Create audit event", description = "Create a new immutable audit event with hash chain linkage")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Event created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditEventResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request - missing required fields"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<AuditEventResponse> createAuditEvent(@Valid @RequestBody CreateAuditEventRequest request) {
        AuditEvent savedEvent = auditEventService.createAuditEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AuditEventResponse.fromEntity(savedEvent));
    }

    @PutMapping("/{id}")
    public ResponseEntity<String> updateNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body("UPDATE is not allowed on audit events - they are immutable");
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> patchNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body("PATCH is not allowed on audit events - they are immutable");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body("DELETE is not allowed on audit events - they are immutable");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidationException(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getAllErrors().stream()
            .map(error -> error.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");

        failureLogger.logValidationFailure("VALIDATION_ERROR", errorMessage, null);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Validation error: " + errorMessage);
    }
}
