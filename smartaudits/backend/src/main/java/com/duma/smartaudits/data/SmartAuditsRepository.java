package com.duma.smartaudits.data;

import com.duma.core.config.ModuleProperties;
import com.duma.core.domain.CoverageResolver;
import com.duma.core.domain.CoverageStatus;
import com.duma.smartaudits.config.TenantDataSourceRegistry;
import com.duma.smartaudits.domain.SmartAuditsDashboard;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SmartAuditsRepository {
  private static final Logger log = LoggerFactory.getLogger(SmartAuditsRepository.class);
  private static final String FACT = "dwh.factSmartauditsCategories";
  private static final String PERIOD =
      """
      WITH period AS (
          SELECT *, CONVERT(date,COALESCE(ReviewAIDate,ReviewDate,SourceLastChangedAt)) AS AuditDate,
                 CASE WHEN ReviewDate IS NOT NULL AND ReviewAIDate IS NOT NULL AND ReviewAIDate>=ReviewDate
                      THEN DATEDIFF(SECOND,ReviewDate,ReviewAIDate)/60.0 END AS ReviewLatencyMinutes
          FROM dwh.factSmartauditsCategories
          WHERE CONVERT(date,COALESCE(ReviewAIDate,ReviewDate,SourceLastChangedAt)) BETWEEN ? AND ?
      )
      """;
  private final ModuleProperties properties;
  private final TenantDataSourceRegistry registry;

  public SmartAuditsRepository(ModuleProperties properties, TenantDataSourceRegistry registry) {
    this.properties = properties;
    this.registry = registry;
  }

  public SmartAuditsDashboard.TenantResult load(String tenantId, LocalDate from, LocalDate to) {
    ModuleProperties.Tenant tenant = properties.getTenants().get(tenantId);
    if (!tenant.isEnabled() || tenant.getDatabase() == null || tenant.getDatabase().isBlank())
      return unavailable(
          tenantId, tenant.getDisplayName(), CoverageStatus.UNAVAILABLE, List.of(), null);
    try {
      JdbcTemplate jdbc = registry.jdbc(tenantId);
      boolean factPresent = exists(jdbc, FACT);
      if (!factPresent) {
        return unavailable(
            tenantId,
            tenant.getDisplayName(),
            CoverageResolver.resolve(factPresent, factPresent, 0),
            List.of(FACT),
            null);
      }
      long days = ChronoUnit.DAYS.between(from, to) + 1;
      LocalDate previousTo = from.minusDays(1);
      LocalDate previousFrom = previousTo.minusDays(days - 1);
      SmartAuditsDashboard.Summary current = summary(jdbc, from, to);
      SmartAuditsDashboard.Summary previous = summary(jdbc, previousFrom, previousTo);
      CoverageStatus status = CoverageResolver.resolve(true, true, current.resultCount());
      if (status == CoverageStatus.NO_DATA) {
        return new SmartAuditsDashboard.TenantResult(
            tenantId,
            tenant.getDisplayName(),
            status,
            List.of(),
            current,
            previous,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            null);
      }
      return new SmartAuditsDashboard.TenantResult(
          tenantId,
          tenant.getDisplayName(),
          status,
          List.of(),
          current,
          previous,
          categories(jdbc, from, to),
          locations(jdbc, from, to),
          recurrentIssues(jdbc, from, to),
          daily(jdbc, from, to),
          sublocations(jdbc, from, to),
          locationCategories(jdbc, from, to),
          taskCategories(jdbc, from, to),
          priorities(jdbc, from, to),
          tasks(jdbc, from, to),
          methods(jdbc, from, to),
          methodCategories(jdbc, from, to),
          models(jdbc, from, to),
          people(jdbc, from, to, "ExecutorName"),
          people(jdbc, from, to, "AuditorName"),
          dataQuality(jdbc, from, to),
          details(jdbc, from, to),
          null);
    } catch (DataAccessException | IllegalArgumentException exception) {
      log.warn(
          "tenant_query_failed module=smartaudits tenant={} error={}",
          tenantId,
          exception.getClass().getSimpleName());
      return unavailable(
          tenantId,
          tenant.getDisplayName(),
          CoverageStatus.UNAVAILABLE,
          List.of(),
          "TENANT_QUERY_FAILED");
    }
  }

  public Freshness freshness(String tenantId) {
    ModuleProperties.Tenant tenant = properties.getTenants().get(tenantId);
    if (!tenant.isEnabled() || tenant.getDatabase() == null || tenant.getDatabase().isBlank())
      return new Freshness(
          tenantId, tenant.getDisplayName(), "factSmartauditsCategories", null, null);
    try {
      List<Freshness> rows =
          registry
              .jdbc(tenantId)
              .query(
                  "SELECT LastRunStatus, LastLoadedAt FROM ctl.IngestionControl WHERE IngestionName = ?",
                  (resultSet, rowNumber) ->
                      new Freshness(
                          tenantId,
                          tenant.getDisplayName(),
                          "factSmartauditsCategories",
                          resultSet.getString("LastRunStatus"),
                          instant(resultSet.getTimestamp("LastLoadedAt"))),
                  "factSmartauditsCategories");
      return rows.isEmpty()
          ? new Freshness(tenantId, tenant.getDisplayName(), "factSmartauditsCategories", null, null)
          : rows.get(0);
    } catch (DataAccessException | IllegalArgumentException exception) {
      log.warn("freshness_query_failed module=smartaudits tenant={}", tenantId);
      return new Freshness(tenantId, tenant.getDisplayName(), "factSmartauditsCategories", null, null);
    }
  }

  public record Freshness(
      String tenantId,
      String tenantName,
      String ingestionName,
      String lastRunStatus,
      java.time.Instant lastLoadedAt) {}

  private SmartAuditsDashboard.TenantResult unavailable(
      String tenantId,
      String tenantName,
      CoverageStatus status,
      List<String> missingSources,
      String errorCode) {
    return new SmartAuditsDashboard.TenantResult(
        tenantId,
        tenantName,
        status,
        missingSources,
        SmartAuditsDashboard.Summary.empty(),
        SmartAuditsDashboard.Summary.empty(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        List.of(),
        errorCode);
  }

  private SmartAuditsDashboard.Summary summary(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForObject(
        """
                WITH period AS (
                    SELECT *,
                           CASE WHEN ReviewDate IS NOT NULL AND ReviewAIDate IS NOT NULL AND ReviewAIDate >= ReviewDate
                                THEN DATEDIFF(SECOND, ReviewDate, ReviewAIDate) / 60.0 END AS ReviewLatencyMinutes
                    FROM dwh.factSmartauditsCategories
                    WHERE CONVERT(date, COALESCE(ReviewAIDate, ReviewDate, SourceLastChangedAt)) BETWEEN ? AND ?
                )
                SELECT COUNT_BIG(*) AS ResultCount,
                       COUNT_BIG(DISTINCT WorkPlanId) AS WorkPlanCount,
                       COUNT_BIG(DISTINCT LocationId) AS LocationCount,
                       COUNT_BIG(DISTINCT [Task]) AS TaskCount,
                       COALESCE(SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS ComplianceResults,
                       COALESCE(SUM(CASE WHEN ResultCategory<>N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS NonComplianceResults,
                       CAST(100.0*SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END)/NULLIF(COUNT_BIG(*),0) AS float) AS ComplianceRate,
                       COALESCE(SUM(EvidenceCount),0) AS EvidenceCount,
                       COALESCE(SUM(FailedEvidenceCount),0) AS FailedEvidenceCount,
                       CAST(100.0*SUM(FailedEvidenceCount)/NULLIF(SUM(EvidenceCount),0) AS float) AS EvidenceFailureRate,
                       COALESCE(SUM(CASE WHEN ResultCategory=N'SIN_CLASIFICAR' THEN 1 ELSE 0 END),0) AS UnclassifiedResults,
                       CAST(100.0*SUM(CASE WHEN ResultCategory<>N'SIN_CLASIFICAR' THEN 1 ELSE 0 END)/NULLIF(COUNT_BIG(*),0) AS float) AS ClassificationCoverageRate,
                       COALESCE(SUM(CASE WHEN ResultCategory IN (N'IMAGEN_NO_LEGIBLE',N'IMAGEN_NO_PROCESABLE') THEN 1 ELSE 0 END),0) AS ImageQualityIssues,
                       COALESCE(SUM(CASE WHEN ResultCategory IN (N'FUERA_DE_RANGO',N'INCUMPLIMIENTO_LIMPIEZA',N'INCUMPLIMIENTO_GENERAL') THEN 1 ELSE 0 END),0) AS OperationalIssues,
                       AVG(CASE WHEN ResultCategory<>N'SIN_CLASIFICAR' THEN CAST(ClassifierConfidence AS float) END) AS AvgClassifierConfidence,
                       AVG(CAST(ReviewLatencyMinutes AS float)) AS AvgReviewLatencyMinutes,
                       MAX(SourceLastChangedAt) AS LatestSourceChangedAt,
                       MAX(ModifiedAt) AS LatestModifiedAt
                FROM period
                """,
        (rs, row) ->
            new SmartAuditsDashboard.Summary(
                rs.getLong("ResultCount"),
                rs.getLong("WorkPlanCount"),
                rs.getLong("LocationCount"),
                rs.getLong("TaskCount"),
                rs.getLong("ComplianceResults"),
                rs.getLong("NonComplianceResults"),
                nullableDouble(rs, "ComplianceRate"),
                rs.getLong("EvidenceCount"),
                rs.getLong("FailedEvidenceCount"),
                nullableDouble(rs, "EvidenceFailureRate"),
                rs.getLong("UnclassifiedResults"),
                nullableDouble(rs, "ClassificationCoverageRate"),
                rs.getLong("ImageQualityIssues"),
                rs.getLong("OperationalIssues"),
                nullableDouble(rs, "AvgClassifierConfidence"),
                nullableDouble(rs, "AvgReviewLatencyMinutes"),
                instant(rs.getTimestamp("LatestSourceChangedAt")),
                instant(rs.getTimestamp("LatestModifiedAt"))),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<SmartAuditsDashboard.Category> categories(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
                WITH period AS (
                    SELECT * FROM dwh.factSmartauditsCategories
                    WHERE CONVERT(date, COALESCE(ReviewAIDate, ReviewDate, SourceLastChangedAt)) BETWEEN ? AND ?
                )
                SELECT ResultCategory,
                       COUNT_BIG(*) AS ResultCount,
                       CAST(100.0*COUNT_BIG(*)/NULLIF((SELECT COUNT_BIG(*) FROM period),0) AS float) AS ResultShare,
                       COUNT_BIG(DISTINCT LocationId) AS LocationCount,
                       COUNT_BIG(DISTINCT [Task]) AS TaskCount,
                       AVG(CAST(ClassifierConfidence AS float)) AS AvgClassifierConfidence
                FROM period
                GROUP BY ResultCategory
                ORDER BY ResultCount DESC, ResultCategory
                """,
        (rs, row) ->
            new SmartAuditsDashboard.Category(
                rs.getString("ResultCategory"),
                rs.getLong("ResultCount"),
                nullableDouble(rs, "ResultShare"),
                rs.getLong("LocationCount"),
                rs.getLong("TaskCount"),
                nullableDouble(rs, "AvgClassifierConfidence")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<SmartAuditsDashboard.Location> locations(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
                WITH period AS (
                    SELECT * FROM dwh.factSmartauditsCategories
                    WHERE CONVERT(date, COALESCE(ReviewAIDate, ReviewDate, SourceLastChangedAt)) BETWEEN ? AND ?
                )
                SELECT TOP (100) p.LocationId,
                       COALESCE(p.LocationName,N'UBICACION_SIN_NOMBRE') AS LocationName,
                       COUNT_BIG(*) AS ResultCount,
                       COUNT_BIG(DISTINCT p.[Task]) AS TaskCount,
                       SUM(CASE WHEN p.ResultCategory<>N'CUMPLIMIENTO' THEN 1 ELSE 0 END) AS NonComplianceResults,
                       CAST(100.0*SUM(CASE WHEN p.ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END)/NULLIF(COUNT_BIG(*),0) AS float) AS ComplianceRate,
                       CAST(100.0*SUM(p.FailedEvidenceCount)/NULLIF(SUM(p.EvidenceCount),0) AS float) AS EvidenceFailureRate,
                       SUM(CASE WHEN p.ResultCategory IN (N'IMAGEN_NO_LEGIBLE',N'IMAGEN_NO_PROCESABLE') THEN 1 ELSE 0 END) AS ImageQualityIssues,
                       SUM(CASE WHEN p.ResultCategory=N'SIN_CLASIFICAR' THEN 1 ELSE 0 END) AS UnclassifiedResults,
                       top_issue.ResultCategory AS TopIssueCategory,
                       COALESCE(top_issue.IssueCount,0) AS TopIssueCount
                FROM period p
                OUTER APPLY (
                    SELECT TOP (1) issue.ResultCategory, COUNT_BIG(*) AS IssueCount
                    FROM period issue
                    WHERE ((issue.LocationId=p.LocationId) OR (issue.LocationId IS NULL AND p.LocationId IS NULL))
                      AND issue.ResultCategory<>N'CUMPLIMIENTO'
                    GROUP BY issue.ResultCategory
                    ORDER BY COUNT_BIG(*) DESC, issue.ResultCategory
                ) top_issue
                GROUP BY p.LocationId, COALESCE(p.LocationName,N'UBICACION_SIN_NOMBRE'), top_issue.ResultCategory, top_issue.IssueCount
                ORDER BY NonComplianceResults DESC, ResultCount DESC, LocationName
                """,
        (rs, row) ->
            new SmartAuditsDashboard.Location(
                rs.getString("LocationId"),
                rs.getString("LocationName"),
                rs.getLong("ResultCount"),
                rs.getLong("TaskCount"),
                rs.getLong("NonComplianceResults"),
                nullableDouble(rs, "ComplianceRate"),
                nullableDouble(rs, "EvidenceFailureRate"),
                rs.getLong("ImageQualityIssues"),
                rs.getLong("UnclassifiedResults"),
                rs.getString("TopIssueCategory"),
                rs.getLong("TopIssueCount")),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<SmartAuditsDashboard.RecurrentIssue> recurrentIssues(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
                WITH period AS (
                    SELECT *, CONVERT(date,COALESCE(ReviewAIDate,ReviewDate,SourceLastChangedAt)) AS AuditDate
                    FROM dwh.factSmartauditsCategories
                    WHERE CONVERT(date, COALESCE(ReviewAIDate, ReviewDate, SourceLastChangedAt)) BETWEEN ? AND ?
                )
                SELECT TOP (100) LocationId,COALESCE(LocationName,N'UBICACION_SIN_NOMBRE') AS LocationName,
                       SublocationId,COALESCE(SublocationName,N'SUBUBICACION_SIN_NOMBRE') AS SublocationName,
                       [Task] AS TaskId,COALESCE(TaskName,N'TAREA_SIN_NOMBRE') AS TaskName,ResultCategory,
                       COUNT_BIG(*) AS RecurrenceCount,COUNT_BIG(DISTINCT WorkPlanId) AS WorkPlanCount,
                       SUM(FailedEvidenceCount) AS FailedEvidenceCount,MIN(AuditDate) AS FirstDate,MAX(AuditDate) AS LastDate
                FROM period
                WHERE ResultCategory<>N'CUMPLIMIENTO'
                GROUP BY LocationId,LocationName,SublocationId,SublocationName,[Task],TaskName,ResultCategory
                HAVING COUNT_BIG(*)>=2
                ORDER BY RecurrenceCount DESC,FailedEvidenceCount DESC,LastDate DESC
                """,
        (rs, row) ->
            new SmartAuditsDashboard.RecurrentIssue(
                rs.getString("LocationId"),
                rs.getString("LocationName"),
                rs.getString("SublocationId"),
                rs.getString("SublocationName"),
                rs.getString("TaskId"),
                rs.getString("TaskName"),
                rs.getString("ResultCategory"),
                rs.getLong("RecurrenceCount"),
                rs.getLong("WorkPlanCount"),
                rs.getLong("FailedEvidenceCount"),
                localDate(rs.getDate("FirstDate")),
                localDate(rs.getDate("LastDate"))),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> daily(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT CONVERT(varchar(10),AuditDate,23) AS MetricDate,COUNT_BIG(*) AS ResultCount,
                   COALESCE(SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS ComplianceResults,
                   COALESCE(SUM(CASE WHEN ResultCategory<>N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS NonComplianceResults,
                   CAST(100.0*SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END)/NULLIF(COUNT_BIG(*),0) AS float) AS ComplianceRate,
                   COALESCE(SUM(EvidenceCount),0) AS EvidenceCount,COALESCE(SUM(FailedEvidenceCount),0) AS FailedEvidenceCount,
                   CAST(100.0*SUM(FailedEvidenceCount)/NULLIF(SUM(EvidenceCount),0) AS float) AS EvidenceFailureRate,
                   COALESCE(SUM(CASE WHEN ResultCategory IN (N'IMAGEN_NO_LEGIBLE',N'IMAGEN_NO_PROCESABLE') THEN 1 ELSE 0 END),0) AS ImageQualityIssues,
                   COALESCE(SUM(CASE WHEN ResultCategory=N'SIN_CLASIFICAR' THEN 1 ELSE 0 END),0) AS UnclassifiedResults,
                   AVG(CAST(ClassifierConfidence AS float)) AS AvgClassifierConfidence,AVG(CAST(ReviewLatencyMinutes AS float)) AS AvgReviewLatencyMinutes
            FROM period GROUP BY AuditDate ORDER BY AuditDate
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> sublocations(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT LocationId,COALESCE(LocationName,N'UBICACION_SIN_NOMBRE') AS LocationName,SublocationId,
                   COALESCE(SublocationName,N'SUBUBICACION_SIN_NOMBRE') AS SublocationName,COUNT_BIG(*) AS ResultCount,
                   COUNT_BIG(DISTINCT [Task]) AS TaskCount,
                   COALESCE(SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS ComplianceResults,
                   COALESCE(SUM(CASE WHEN ResultCategory<>N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS NonComplianceResults,
                   CAST(100.0*SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END)/NULLIF(COUNT_BIG(*),0) AS float) AS ComplianceRate,
                   COALESCE(SUM(EvidenceCount),0) AS EvidenceCount,COALESCE(SUM(FailedEvidenceCount),0) AS FailedEvidenceCount,
                   CAST(100.0*SUM(FailedEvidenceCount)/NULLIF(SUM(EvidenceCount),0) AS float) AS EvidenceFailureRate,
                   COALESCE(SUM(CASE WHEN ResultCategory IN (N'IMAGEN_NO_LEGIBLE',N'IMAGEN_NO_PROCESABLE') THEN 1 ELSE 0 END),0) AS ImageQualityIssues,
                   CONVERT(varchar(10),MAX(AuditDate),23) AS LatestDate
            FROM period
            GROUP BY LocationId,LocationName,SublocationId,SublocationName
            ORDER BY NonComplianceResults DESC,ResultCount DESC,LocationName,SublocationName
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> locationCategories(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT LocationId,COALESCE(LocationName,N'UBICACION_SIN_NOMBRE') AS LocationName,ResultCategory,COUNT_BIG(*) AS ResultCount
            FROM period GROUP BY LocationId,LocationName,ResultCategory ORDER BY LocationName,ResultCategory
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> taskCategories(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT COALESCE(TaskCategory,N'SIN_CATEGORIA') AS TaskCategory,COUNT_BIG(*) AS ResultCount,
                   COUNT_BIG(DISTINCT [Task]) AS TaskCount,COUNT_BIG(DISTINCT LocationId) AS LocationCount,
                   COALESCE(SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS ComplianceResults,
                   COALESCE(SUM(CASE WHEN ResultCategory<>N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS NonComplianceResults,
                   CAST(100.0*SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END)/NULLIF(COUNT_BIG(*),0) AS float) AS ComplianceRate,
                   COALESCE(SUM(EvidenceCount),0) AS EvidenceCount,COALESCE(SUM(FailedEvidenceCount),0) AS FailedEvidenceCount,
                   CAST(100.0*SUM(FailedEvidenceCount)/NULLIF(SUM(EvidenceCount),0) AS float) AS EvidenceFailureRate
            FROM period GROUP BY COALESCE(TaskCategory,N'SIN_CATEGORIA')
            ORDER BY NonComplianceResults DESC,ResultCount DESC,TaskCategory
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> priorities(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT COALESCE(Priority,N'SIN_PRIORIDAD') AS Priority,COUNT_BIG(*) AS ResultCount,
                   COUNT_BIG(DISTINCT [Task]) AS TaskCount,COUNT_BIG(DISTINCT LocationId) AS LocationCount,
                   COALESCE(SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS ComplianceResults,
                   COALESCE(SUM(CASE WHEN ResultCategory<>N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS NonComplianceResults,
                   CAST(100.0*SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END)/NULLIF(COUNT_BIG(*),0) AS float) AS ComplianceRate,
                   COALESCE(SUM(EvidenceCount),0) AS EvidenceCount,COALESCE(SUM(FailedEvidenceCount),0) AS FailedEvidenceCount,
                   CAST(100.0*SUM(FailedEvidenceCount)/NULLIF(SUM(EvidenceCount),0) AS float) AS EvidenceFailureRate
            FROM period GROUP BY COALESCE(Priority,N'SIN_PRIORIDAD')
            ORDER BY NonComplianceResults DESC,ResultCount DESC,Priority
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> tasks(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT TOP (150) p.[Task] AS TaskId,COALESCE(p.TaskName,N'TAREA_SIN_NOMBRE') AS TaskName,
                   p.[Checkpoint] AS CheckpointId,COALESCE(p.CheckpointName,N'CHECKPOINT_SIN_NOMBRE') AS CheckpointName,
                   COALESCE(p.TaskCategory,N'SIN_CATEGORIA') AS TaskCategory,COALESCE(p.Priority,N'SIN_PRIORIDAD') AS Priority,
                   COUNT_BIG(*) AS ResultCount,COUNT_BIG(DISTINCT p.LocationId) AS LocationCount,
                   COALESCE(SUM(CASE WHEN p.ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS ComplianceResults,
                   COALESCE(SUM(CASE WHEN p.ResultCategory<>N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS NonComplianceResults,
                   CAST(100.0*SUM(CASE WHEN p.ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END)/NULLIF(COUNT_BIG(*),0) AS float) AS ComplianceRate,
                   COALESCE(SUM(p.EvidenceCount),0) AS EvidenceCount,COALESCE(SUM(p.FailedEvidenceCount),0) AS FailedEvidenceCount,
                   CAST(100.0*SUM(p.FailedEvidenceCount)/NULLIF(SUM(p.EvidenceCount),0) AS float) AS EvidenceFailureRate,
                   top_issue.ResultCategory AS TopIssueCategory,COALESCE(top_issue.IssueCount,0) AS TopIssueCount,CONVERT(varchar(10),MAX(p.AuditDate),23) AS LatestDate
            FROM period AS p
            OUTER APPLY (SELECT TOP (1) issue.ResultCategory,COUNT_BIG(*) AS IssueCount FROM period AS issue
                         WHERE ((issue.[Task]=p.[Task]) OR (issue.[Task] IS NULL AND p.[Task] IS NULL)) AND issue.ResultCategory<>N'CUMPLIMIENTO'
                         GROUP BY issue.ResultCategory ORDER BY COUNT_BIG(*) DESC,issue.ResultCategory) AS top_issue
            GROUP BY p.[Task],p.TaskName,p.[Checkpoint],p.CheckpointName,p.TaskCategory,p.Priority,top_issue.ResultCategory,top_issue.IssueCount
            ORDER BY NonComplianceResults DESC,ResultCount DESC,TaskName
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> methods(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT COALESCE(ClassificationMethod,N'SIN_CLASIFICAR') AS ClassificationMethod,COUNT_BIG(*) AS ResultCount,
                   COALESCE(SUM(CASE WHEN ResultCategory<>N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS NonComplianceResults,
                   COUNT_BIG(DISTINCT ResultCategory) AS CategoryCount,COUNT_BIG(DISTINCT ClassifierModelVersion) AS ModelCount,
                   AVG(CAST(ClassifierConfidence AS float)) AS AvgConfidence,MIN(CAST(ClassifierConfidence AS float)) AS MinConfidence,
                   MAX(CAST(ClassifierConfidence AS float)) AS MaxConfidence,CONVERT(varchar(10),MAX(AuditDate),23) AS LatestDate
            FROM period GROUP BY COALESCE(ClassificationMethod,N'SIN_CLASIFICAR')
            ORDER BY ResultCount DESC,ClassificationMethod
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> methodCategories(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT COALESCE(ClassificationMethod,N'SIN_CLASIFICAR') AS ClassificationMethod,ResultCategory,
                   COUNT_BIG(*) AS ResultCount,AVG(CAST(ClassifierConfidence AS float)) AS AvgConfidence
            FROM period GROUP BY COALESCE(ClassificationMethod,N'SIN_CLASIFICAR'),ResultCategory
            ORDER BY ClassificationMethod,ResultCategory
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> models(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT COALESCE(ClassifierModelVersion,N'SIN_MODELO') AS ClassifierModelVersion,
                   COALESCE(ClassificationMethod,N'SIN_CLASIFICAR') AS ClassificationMethod,COUNT_BIG(*) AS ResultCount,
                   COUNT_BIG(DISTINCT ResultCategory) AS CategoryCount,AVG(CAST(ClassifierConfidence AS float)) AS AvgConfidence,
                   MIN(CAST(ClassifierConfidence AS float)) AS MinConfidence,MAX(CAST(ClassifierConfidence AS float)) AS MaxConfidence,
                   CONVERT(varchar(10),MIN(AuditDate),23) AS FirstDate,CONVERT(varchar(10),MAX(AuditDate),23) AS LastDate
            FROM period GROUP BY COALESCE(ClassifierModelVersion,N'SIN_MODELO'),COALESCE(ClassificationMethod,N'SIN_CLASIFICAR')
            ORDER BY ResultCount DESC,ClassifierModelVersion
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private List<Map<String, Object>> people(
      JdbcTemplate jdbc, LocalDate from, LocalDate to, String column) {
    String emptyName = column.equals("ExecutorName") ? "EJECUTOR_SIN_NOMBRE" : "AUDITOR_SIN_NOMBRE";
    String sql =
        PERIOD
            + """
            SELECT TOP (80) COALESCE(%s,N'%s') AS %s,COUNT_BIG(*) AS ResultCount,
                   COUNT_BIG(DISTINCT LocationId) AS LocationCount,
                   COALESCE(SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS ComplianceResults,
                   COALESCE(SUM(CASE WHEN ResultCategory<>N'CUMPLIMIENTO' THEN 1 ELSE 0 END),0) AS NonComplianceResults,
                   CAST(100.0*SUM(CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END)/NULLIF(COUNT_BIG(*),0) AS float) AS ComplianceRate,
                   COALESCE(SUM(EvidenceCount),0) AS EvidenceCount,COALESCE(SUM(FailedEvidenceCount),0) AS FailedEvidenceCount,
                   CAST(100.0*SUM(FailedEvidenceCount)/NULLIF(SUM(EvidenceCount),0) AS float) AS EvidenceFailureRate,
                   CONVERT(varchar(10),MAX(AuditDate),23) AS LatestDate
            FROM period GROUP BY COALESCE(%s,N'%s')
            ORDER BY NonComplianceResults DESC,ResultCount DESC,%s
            """
                .formatted(column, emptyName, column, column, emptyName, column);
    return jdbc.queryForList(sql, Date.valueOf(from), Date.valueOf(to));
  }

  private Map<String, Object> dataQuality(
      JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            PERIOD
                + """
                SELECT COALESCE(SUM(CASE WHEN LocationId IS NULL THEN 1 ELSE 0 END),0) AS MissingLocationId,
                       COALESCE(SUM(CASE WHEN LocationName IS NULL OR LTRIM(RTRIM(LocationName))='' THEN 1 ELSE 0 END),0) AS MissingLocationName,
                       COALESCE(SUM(CASE WHEN SublocationId IS NULL THEN 1 ELSE 0 END),0) AS MissingSublocationId,
                       COALESCE(SUM(CASE WHEN SublocationName IS NULL OR LTRIM(RTRIM(SublocationName))='' THEN 1 ELSE 0 END),0) AS MissingSublocationName,
                       COALESCE(SUM(CASE WHEN [Task] IS NULL THEN 1 ELSE 0 END),0) AS MissingTaskId,
                       COALESCE(SUM(CASE WHEN TaskName IS NULL OR LTRIM(RTRIM(TaskName))='' THEN 1 ELSE 0 END),0) AS MissingTaskName,
                       COALESCE(SUM(CASE WHEN ExecutorName IS NULL OR LTRIM(RTRIM(ExecutorName))='' THEN 1 ELSE 0 END),0) AS MissingExecutorName,
                       COALESCE(SUM(CASE WHEN AuditorName IS NULL OR LTRIM(RTRIM(AuditorName))='' THEN 1 ELSE 0 END),0) AS MissingAuditorName,
                       COALESCE(SUM(CASE WHEN TaskCategory IS NULL OR TaskCategory=N'SIN_CATEGORIA' THEN 1 ELSE 0 END),0) AS MissingTaskCategory,
                       COALESCE(SUM(CASE WHEN Priority IS NULL OR LTRIM(RTRIM(Priority))='' THEN 1 ELSE 0 END),0) AS MissingPriority,
                       COALESCE(SUM(CASE WHEN AiResult IS NULL THEN 1 ELSE 0 END),0) AS MissingAiResult,
                       COALESCE(SUM(CASE WHEN ReviewAIDate IS NULL THEN 1 ELSE 0 END),0) AS MissingReviewAiDate,
                       COALESCE(SUM(CASE WHEN EvidenceCount=0 THEN 1 ELSE 0 END),0) AS ResultsWithoutEvidence,
                       COALESCE(SUM(CASE WHEN ResultCategory=N'SIN_CLASIFICAR' THEN 1 ELSE 0 END),0) AS UnclassifiedResults,
                       COALESCE(SUM(CASE WHEN ClassifierConfidence IS NULL THEN 1 ELSE 0 END),0) AS MissingClassifierConfidence,
                       COALESCE(SUM(CASE WHEN ReviewDate IS NOT NULL AND ReviewAIDate IS NOT NULL AND ReviewAIDate<ReviewDate THEN 1 ELSE 0 END),0) AS NegativeReviewLatency,
                       COALESCE(SUM(CASE WHEN (AiResult=1 AND ResultCategory<>N'CUMPLIMIENTO') OR (AiResult=0 AND ResultCategory=N'CUMPLIMIENTO') THEN 1 ELSE 0 END),0) AS ResultMismatch
                FROM period
                """,
            Date.valueOf(from),
            Date.valueOf(to));
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }

  private List<Map<String, Object>> details(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        PERIOD
            + """
            SELECT TOP (500) PlanResultId,WorkPlanId,EvidencePhotoId,LocationId,SublocationId,[Checkpoint] AS CheckpointId,[Task] AS TaskId,
                   LocationName,SublocationName,CheckpointName,TaskName,ExecutorName,AuditorName,
                   COALESCE(TaskCategory,N'SIN_CATEGORIA') AS TaskCategory,COALESCE(Priority,N'SIN_PRIORIDAD') AS Priority,
                   ResultCategory,AiResult,CONVERT(varchar(19),ReviewDate,126) AS ReviewDate,
                   CONVERT(varchar(19),ReviewAIDate,126) AS ReviewAIDate,CONVERT(varchar(19),SourceLastChangedAt,126) AS SourceLastChangedAt,
                   COALESCE(ClassificationMethod,N'SIN_CLASIFICAR') AS ClassificationMethod,ClassifierModelVersion,
                   CAST(ClassifierConfidence AS float) AS ClassifierConfidence,FailedEvidenceCount,EvidenceCount,
                   CAST(CASE WHEN EvidenceCount>0 THEN 100.0*FailedEvidenceCount/EvidenceCount END AS float) AS EvidenceFailureRate,
                   CAST(ReviewLatencyMinutes AS float) AS ReviewLatencyMinutes,CONVERT(varchar(19),CreatedAt,126) AS CreatedAt,
                   CONVERT(varchar(19),ModifiedAt,126) AS ModifiedAt
            FROM period
            ORDER BY CASE WHEN ResultCategory=N'CUMPLIMIENTO' THEN 1 ELSE 0 END,FailedEvidenceCount DESC,AuditDate DESC,PlanResultId
            """,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private boolean exists(JdbcTemplate jdbc, String objectName) {
    Integer value =
        jdbc.queryForObject(
            "SELECT CASE WHEN OBJECT_ID(?,N'U') IS NULL THEN 0 ELSE 1 END",
            Integer.class,
            objectName);
    return value != null && value == 1;
  }

  private Double nullableDouble(ResultSet rs, String column) throws SQLException {
    double value = rs.getDouble(column);
    return rs.wasNull() ? null : value;
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
  }

  private LocalDate localDate(Date value) {
    return value == null ? null : value.toLocalDate();
  }
}
