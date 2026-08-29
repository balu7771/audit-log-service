package com.persistent.auditlog.api;

import com.persistent.auditlog.service.AuditEventRetentionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/audit/retention")
@RequiredArgsConstructor
@Tag(name = "Retention", description = "Archival of records older than a configurable retention window")
public class AuditRetentionController {

    private final AuditEventRetentionService auditEventRetentionService;

    @PostMapping("/archive")
    @Operation(summary = "Archive eligible records",
        description = "Soft-archives (archived_at flag) records older than the retention window; the hash chain is never touched")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Archival applied",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArchiveResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid windowDays")
    })
    public ResponseEntity<ArchiveResponse> archiveEligibleRecords(@RequestParam(required = false) Integer windowDays) {
        if (windowDays != null && windowDays < 0) {
            throw new IllegalArgumentException("windowDays must be non-negative");
        }
        AuditEventRetentionService.ArchiveResult result =
            auditEventRetentionService.archiveEligibleRecords(Optional.ofNullable(windowDays));

        return ResponseEntity.ok(ArchiveResponse.builder()
            .archivedCount(result.archivedCount())
            .cutoffTimestamp(result.cutoff())
            .windowDaysUsed(result.windowDaysUsed())
            .build());
    }
}
