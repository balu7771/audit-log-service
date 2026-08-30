package com.persistent.auditlog.api;

import com.persistent.auditlog.service.AuditExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit/export")
@RequiredArgsConstructor
@Tag(name = "Audit Export", description = "Self-contained, independently verifiable export bundles")
public class AuditExportController {

    private final AuditExportService auditExportService;

    @GetMapping
    @PreAuthorize("hasRole('AUDITOR')")
    @Operation(summary = "Export records as a verifiable bundle",
        description = "Exports all records for a given actorId, or resourceType(+resourceId), as a self-contained hash-and-signature verifiable bundle")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Bundle produced",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportBundleResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid filter combination or export too large")
    })
    public ResponseEntity<ExportBundleResponse> exportBundle(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId) {
        return ResponseEntity.ok(auditExportService.exportBundle(actorId, resourceType, resourceId));
    }

    @PostMapping("/verify")
    @PreAuthorize("hasRole('AUDITOR')")
    @Operation(summary = "Verify a previously exported bundle",
        description = "Recomputes per-record hashes, the manifest hash, and the HMAC signature to independently confirm nothing in the bundle was altered since export")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Verification complete",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportVerificationResponse.class)))
    })
    public ResponseEntity<ExportVerificationResponse> verifyBundle(@RequestBody ExportBundleResponse bundle) {
        return ResponseEntity.ok(auditExportService.verifyBundle(bundle));
    }
}
