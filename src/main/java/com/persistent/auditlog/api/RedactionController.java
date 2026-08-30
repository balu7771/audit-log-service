package com.persistent.auditlog.api;

import com.persistent.auditlog.service.RedactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit/events")
@RequiredArgsConstructor
@Tag(name = "Redaction", description = "Crypto-shredding redaction of declared-sensitive payload fields")
public class RedactionController {

    private final RedactionService redactionService;

    @PostMapping("/{sequenceId}/redactions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Redact a sensitive field",
        description = "Irreversibly destroys the decryption key for a declared-sensitive field, without altering the hash chain")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Field redacted (or was already redacted)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RedactionResponse.class))),
        @ApiResponse(responseCode = "400", description = "Field was not declared sensitive at creation, or record not found")
    })
    public ResponseEntity<RedactionResponse> redactField(@PathVariable Long sequenceId,
                                                          @Valid @RequestBody RedactFieldRequest request) {
        RedactionService.RedactionResult result = redactionService.redactField(
            sequenceId, request.getFieldPath(), request.getActorId(), request.getReason());

        return ResponseEntity.ok(RedactionResponse.builder()
            .sequenceId(result.sequenceId())
            .fieldPath(result.fieldPath())
            .alreadyRedacted(result.alreadyRedacted())
            .redactionAuditEventSequenceId(result.redactionAuditEventSequenceId())
            .build());
    }
}
