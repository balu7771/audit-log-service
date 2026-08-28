package com.persistent.auditlog.api;

import com.persistent.auditlog.service.AuditEventChainVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
@Tag(name = "Audit Chain", description = "Hash-chain integrity verification")
public class AuditChainController {

    private final AuditEventChainVerificationService auditEventChainVerificationService;

    @GetMapping("/verify")
    @Operation(summary = "Verify chain integrity", description = "Verify the entire audit event chain or last N records")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Verification complete",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = VerificationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<VerificationResponse> verifyChain(@RequestParam(required = false) Integer lastN) {
        VerificationResponse result = auditEventChainVerificationService.verifyChain(Optional.ofNullable(lastN));
        return ResponseEntity.ok(result);
    }
}
