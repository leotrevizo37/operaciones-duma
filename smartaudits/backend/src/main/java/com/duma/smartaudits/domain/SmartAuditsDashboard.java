package com.duma.smartaudits.domain;

import com.duma.core.domain.CoverageStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class SmartAuditsDashboard {
  private SmartAuditsDashboard() {}

  public record Response(
      Instant generatedAt, LocalDate from, LocalDate to, List<TenantResult> tenants) {}

  public record TenantResult(
      String tenantId,
      String tenantName,
      CoverageStatus coverageStatus,
      List<String> missingSources,
      Summary current,
      Summary previous,
      List<Category> categories,
      List<Location> locations,
      List<RecurrentIssue> recurrentIssues,
      List<Map<String, Object>> daily,
      List<Map<String, Object>> sublocations,
      List<Map<String, Object>> locationCategories,
      List<Map<String, Object>> taskCategories,
      List<Map<String, Object>> priorities,
      List<Map<String, Object>> tasks,
      List<Map<String, Object>> methods,
      List<Map<String, Object>> methodCategories,
      List<Map<String, Object>> models,
      List<Map<String, Object>> executors,
      List<Map<String, Object>> auditors,
      Map<String, Object> dataQuality,
      List<Map<String, Object>> details,
      String errorCode) {}

  public record Summary(
      long resultCount,
      long workPlanCount,
      long locationCount,
      long taskCount,
      long complianceResults,
      long nonComplianceResults,
      Double complianceRate,
      long evidenceCount,
      long failedEvidenceCount,
      Double evidenceFailureRate,
      long unclassifiedResults,
      Double classificationCoverageRate,
      long imageQualityIssues,
      long operationalIssues,
      Double avgClassifierConfidence,
      Double avgReviewLatencyMinutes,
      Instant latestSourceChangedAt,
      Instant latestModifiedAt) {
    public static Summary empty() {
      return new Summary(0, 0, 0, 0, 0, 0, null, 0, 0, null, 0, null, 0, 0, null, null, null, null);
    }
  }

  public record Category(
      String resultCategory,
      long resultCount,
      Double resultShare,
      long locationCount,
      long taskCount,
      Double avgClassifierConfidence) {}

  public record Location(
      String locationId,
      String locationName,
      long resultCount,
      long taskCount,
      long nonComplianceResults,
      Double complianceRate,
      Double evidenceFailureRate,
      long imageQualityIssues,
      long unclassifiedResults,
      String topIssueCategory,
      long topIssueCount) {}

  public record RecurrentIssue(
      String locationId,
      String locationName,
      String sublocationId,
      String sublocationName,
      String taskId,
      String taskName,
      String resultCategory,
      long recurrenceCount,
      long workPlanCount,
      long failedEvidenceCount,
      LocalDate firstDate,
      LocalDate lastDate) {}
}
