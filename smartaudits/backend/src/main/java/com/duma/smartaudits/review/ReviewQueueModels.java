package com.duma.smartaudits.review;

import java.time.Instant;
import java.util.List;

public final class ReviewQueueModels {
  private ReviewQueueModels() {}

  public record Page(List<Item> items, long totalCount, int page, int pageSize) {}

  public record Item(
      String normalizedCommentHash,
      int aiResult,
      String sampleComment,
      String normalizedComment,
      int candidateCount,
      Instant firstSeenAt,
      Instant lastSeenAt,
      Long lastPlanResultId,
      Long lastEvidencePhotoId,
      String suggestedCategory,
      String suggestedMethod,
      Double suggestedConfidence,
      String reviewStatus) {}

  public record Approval(
      String normalizedCommentHash,
      int aiResult,
      String reviewStatus,
      String reviewedResultCategory,
      String reviewedBy,
      Instant reviewedAt,
      boolean idempotent) {}
}
