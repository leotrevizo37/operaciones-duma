package com.duma.smartaudits.review;

import com.duma.smartaudits.audit.ReviewAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/api/smartaudits/review-queue")
public class ReviewQueueController {
  private final ReviewQueueRepository repository;
  private final ReviewAuditService logs;

  public ReviewQueueController(ReviewQueueRepository repository, ReviewAuditService logs) {
    this.repository = repository;
    this.logs = logs;
  }

  @GetMapping
  public ReviewQueueModels.Page pending(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int pageSize) {
    return repository.pending(page, pageSize);
  }

  @PostMapping("/approve")
  public ReviewQueueModels.Approval approve(
      @Valid @RequestBody ApprovalRequest request,
      Authentication authentication,
      HttpServletRequest servletRequest) {
    if (!(authentication instanceof JwtAuthenticationToken jwt)) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Se requiere identidad emitida por el shell.");
    }
    String reviewedBy = jwt.getToken().getSubject();
    if (reviewedBy == null || reviewedBy.isBlank() || reviewedBy.length() > 255) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "La identidad autenticada no es valida.");
    }
    ReviewQueueModels.Approval approval =
        repository.approve(
            request.normalizedCommentHash(),
            request.aiResult(),
            request.resultCategory(),
            reviewedBy,
            request.reviewNotes());
    logs.recordApproval(reviewedBy, approval.idempotent(), servletRequest);
    return approval;
  }

  @ExceptionHandler(ReviewQueueNotFoundException.class)
  ResponseEntity<Map<String, String>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("code", "SMARTAUDITS_REVIEW_NOT_FOUND"));
  }

  @ExceptionHandler(ReviewQueueConflictException.class)
  ResponseEntity<Map<String, String>> conflict() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("code", "SMARTAUDITS_REVIEW_CONFLICT"));
  }

  public record ApprovalRequest(
      @NotNull @Pattern(regexp = "^[0-9a-fA-F]{64}$") String normalizedCommentHash,
      @Min(0) @Max(0) int aiResult,
      @NotNull PromotableCategory resultCategory,
      @Size(max = 1000) String reviewNotes) {}
}
