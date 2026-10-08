package com.duma.smartaudits.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duma.core.config.ModuleProperties;
import com.duma.smartaudits.config.TenantDataSourceRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewQueueRepositoryIT {
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);
  private static final String HASH_C = "c".repeat(64);

  @Container
  private static final MSSQLServerContainer<?> SQL_SERVER =
      new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

  private ReviewQueueRepository repository;
  private JdbcTemplate jdbc;

  @BeforeAll
  void connect() {
    ModuleProperties properties = new ModuleProperties();
    properties.getWarehouse().setHost(SQL_SERVER.getHost());
    properties.getWarehouse().setPort(SQL_SERVER.getFirstMappedPort());
    properties.getWarehouse().setUsername(SQL_SERVER.getUsername());
    properties.getWarehouse().setPassword(SQL_SERVER.getPassword());
    properties.getWarehouse().setEncrypt(false);
    properties.getWarehouse().setTrustServerCertificate(true);
    ModuleProperties.Tenant carlsjr = new ModuleProperties.Tenant();
    carlsjr.setDisplayName("Carls Jr");
    carlsjr.setDatabase("master");
    properties.getTenants().put("carlsjr", carlsjr);
    TenantDataSourceRegistry registry = new TenantDataSourceRegistry(properties);
    repository = new ReviewQueueRepository(registry);
    jdbc = registry.jdbc("carlsjr");
    jdbc.execute("IF SCHEMA_ID(N'ctl') IS NULL EXEC(N'CREATE SCHEMA ctl')");
  }

  @BeforeEach
  void resetTables() {
    jdbc.execute(
        "IF OBJECT_ID(N'ctl.SmartauditsAiCommentLookup',N'U') IS NOT NULL DROP TABLE ctl.SmartauditsAiCommentLookup");
    jdbc.execute(
        "IF OBJECT_ID(N'ctl.SmartauditsAiCommentReviewQueue',N'U') IS NOT NULL DROP TABLE ctl.SmartauditsAiCommentReviewQueue");
    jdbc.execute(
        """
                CREATE TABLE ctl.SmartauditsAiCommentReviewQueue(
                    NormalizedCommentHash char(64) NOT NULL,
                    AiResult tinyint NOT NULL,
                    NormalizedComment nvarchar(2000) NOT NULL,
                    SampleComment nvarchar(2000) NULL,
                    CandidateCount int NOT NULL,
                    FirstSeenAt datetime2(0) NULL,
                    LastSeenAt datetime2(0) NULL,
                    LastPlanResultId bigint NULL,
                    LastEvidencePhotoId bigint NULL,
                    SuggestedCategory nvarchar(100) NULL,
                    SuggestedMethod nvarchar(50) NULL,
                    SuggestedConfidence decimal(9,6) NULL,
                    ReviewStatus nvarchar(20) NOT NULL,
                    ReviewedResultCategory nvarchar(100) NULL,
                    ReviewedBy nvarchar(255) NULL,
                    ReviewedAt datetime2(0) NULL,
                    ReviewNotes nvarchar(1000) NULL,
                    CreatedAt datetime2(0) NOT NULL DEFAULT SYSUTCDATETIME(),
                    ModifiedAt datetime2(0) NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT PK_review_queue PRIMARY KEY(NormalizedCommentHash,AiResult)
                )
                """);
    jdbc.execute(
        """
                CREATE TABLE ctl.SmartauditsAiCommentLookup(
                    NormalizedCommentHash char(64) NOT NULL,
                    NormalizedComment nvarchar(2000) NOT NULL,
                    AiResult tinyint NOT NULL,
                    ResultCategory nvarchar(100) NOT NULL,
                    ClassificationMethod nvarchar(50) NOT NULL,
                    ClassifierModelVersion nvarchar(100) NULL,
                    ClassifierConfidence decimal(9,6) NULL,
                    ClassifiedAt datetime2(0) NOT NULL DEFAULT SYSUTCDATETIME(),
                    ModifiedAt datetime2(0) NOT NULL DEFAULT SYSUTCDATETIME(),
                    CONSTRAINT PK_comment_lookup PRIMARY KEY(NormalizedCommentHash,AiResult)
                )
                """);
  }

  @Test
  void listsOnlyPendingRowsInDeterministicPriorityOrder() {
    insertQueue(HASH_A, 0, 3, "2026-01-01T10:00:00Z", "PENDING");
    insertQueue(HASH_B, 0, 8, "2026-01-01T10:00:00Z", "PENDING");
    insertQueue(HASH_C, 0, 8, "2026-01-02T10:00:00Z", "PENDING");
    insertQueue("d".repeat(64), 0, 100, "2026-01-03T10:00:00Z", "PROMOTED");

    ReviewQueueModels.Page page = repository.pending(0, 25);

    assertThat(page.totalCount()).isEqualTo(3);
    assertThat(page.items())
        .extracting(ReviewQueueModels.Item::normalizedCommentHash)
        .containsExactly(HASH_C, HASH_B, HASH_A);
    assertThat(page.items()).allMatch(item -> item.reviewStatus().equals("PENDING"));
  }

  @Test
  void approvesOnlyRequestedCompositeKeyAndIsIdempotentForSameHumanCategory() {
    insertQueue(HASH_A, 0, 5, "2026-01-02T10:00:00Z", "PENDING");
    insertQueue(HASH_A, 1, 2, "2026-01-01T10:00:00Z", "PENDING");

    ReviewQueueModels.Approval first =
        repository.approve(
            HASH_A,
            0,
            PromotableCategory.INCUMPLIMIENTO_GENERAL,
            "reviewer-1",
            "Evidencia revisada");
    Timestamp modifiedAt =
        jdbc.queryForObject(
            "SELECT ModifiedAt FROM ctl.SmartauditsAiCommentReviewQueue WHERE NormalizedCommentHash=? AND AiResult=0",
            Timestamp.class,
            HASH_A);
    ReviewQueueModels.Approval repeated =
        repository.approve(
            HASH_A,
            0,
            PromotableCategory.INCUMPLIMIENTO_GENERAL,
            "reviewer-1",
            "La repeticion no debe modificar");

    assertThat(first.reviewStatus()).isEqualTo("APPROVED");
    assertThat(first.reviewedResultCategory()).isEqualTo("INCUMPLIMIENTO_GENERAL");
    assertThat(first.idempotent()).isFalse();
    assertThat(repeated.idempotent()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT ModifiedAt FROM ctl.SmartauditsAiCommentReviewQueue WHERE NormalizedCommentHash=? AND AiResult=0",
                Timestamp.class,
                HASH_A))
        .isEqualTo(modifiedAt);
    assertThat(
            jdbc.queryForObject(
                "SELECT ReviewStatus FROM ctl.SmartauditsAiCommentReviewQueue WHERE NormalizedCommentHash=? AND AiResult=1",
                String.class,
                HASH_A))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ctl.SmartauditsAiCommentLookup", Integer.class))
        .isZero();
  }

  @Test
  void rejectsASecondApprovalWithAnotherCategory() {
    insertQueue(HASH_B, 0, 4, "2026-01-02T10:00:00Z", "PENDING");
    repository.approve(
        HASH_B, 0, PromotableCategory.INCUMPLIMIENTO_GENERAL, "reviewer-2", null);

    assertThatThrownBy(
            () ->
                repository.approve(
                    HASH_B, 0, PromotableCategory.FUERA_DE_RANGO, "reviewer-2", null))
        .isInstanceOf(ReviewQueueConflictException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT ReviewStatus FROM ctl.SmartauditsAiCommentReviewQueue WHERE NormalizedCommentHash=? AND AiResult=0",
                String.class,
                HASH_B))
        .isEqualTo("APPROVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT ReviewedResultCategory FROM ctl.SmartauditsAiCommentReviewQueue WHERE NormalizedCommentHash=? AND AiResult=0",
                String.class,
                HASH_B))
        .isEqualTo("INCUMPLIMIENTO_GENERAL");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ctl.SmartauditsAiCommentLookup", Integer.class))
        .isZero();
  }

  private void insertQueue(String hash, int aiResult, int count, String lastSeenAt, String status) {
    jdbc.update(
        """
                INSERT INTO ctl.SmartauditsAiCommentReviewQueue(
                    NormalizedCommentHash,AiResult,NormalizedComment,SampleComment,CandidateCount,
                    FirstSeenAt,LastSeenAt,LastPlanResultId,LastEvidencePhotoId,SuggestedCategory,
                    SuggestedMethod,SuggestedConfidence,ReviewStatus)
                VALUES(?,?,N'comentario normalizado',N'Comentario de muestra',?,DATEADD(DAY,-2,?),?,101,202,
                       N'INCUMPLIMIENTO_GENERAL',N'ML',0.8,?)
                """,
        hash,
        aiResult,
        count,
        Timestamp.from(Instant.parse(lastSeenAt)),
        Timestamp.from(Instant.parse(lastSeenAt)),
        status);
  }
}
