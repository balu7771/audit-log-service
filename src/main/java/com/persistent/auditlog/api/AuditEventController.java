package com.persistent.auditlog.api;

import com.persistent.auditlog.domain.AuditEvent;
import com.persistent.auditlog.service.AuditEventQueryService;
import com.persistent.auditlog.service.AuditEventService;
import com.persistent.auditlog.service.PayloadRedactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/audit/events")
@RequiredArgsConstructor
@Tag(name = "Audit Events", description = "Append-only audit event write and query APIs")
public class AuditEventController {

    private final AuditEventService auditEventService;
    private final AuditEventQueryService auditEventQueryService;
    private final PayloadRedactionService payloadRedactionService;

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
        return ResponseEntity.status(HttpStatus.CREATED).body(payloadRedactionService.renderForRead(savedEvent));
    }

    @GetMapping
    @Operation(summary = "Query audit events", description = "Query audit events with optional filters and pagination")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Query successful",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaginatedAuditEventsResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid query parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PaginatedAuditEventsResponse> queryAuditEvents(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AuditEvent> results = auditEventQueryService.queryAuditEvents(
            actorId, resourceType, resourceId, eventType, from, to, includeArchived, page, size);

        List<AuditEventResponse> rendered = payloadRedactionService.renderPage(results.getContent());
        Page<AuditEventResponse> responsePage = new PageImpl<>(rendered, results.getPageable(), results.getTotalElements());
        return ResponseEntity.ok(PaginatedAuditEventsResponse.fromPage(responsePage));
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
}
