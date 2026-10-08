package com.duma.smartaudits.review;

import com.duma.smartaudits.config.TenantDataSourceRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewQueueRepository {
  private static final String TENANT = "carlsjr";
  private final TenantDataSourceRegistry registry;

  public ReviewQueueRepository(TenantDataSourceRegistry registry) {
    this.registry = registry;
  }

  public ReviewQueueModels.Page pending(int page, int pageSize) {
    JdbcTemplate jdbc = registry.jdbc(TENANT);
    List<ReviewQueueModels.Item> items = new ArrayList<>();
    long[] total = {0};
    jdbc.query(
        """
                SELECT NormalizedCommentHash,AiResult,SampleComment,NormalizedComment,CandidateCount,
                       FirstSeenAt,LastSeenAt,LastPlanResultId,LastEvidencePhotoId,SuggestedCategory,
                       SuggestedMethod,SuggestedConfidence,ReviewStatus,COUNT_BIG(*) OVER () AS TotalCount
                FROM ctl.SmartauditsAiCommentReviewQueue
                WHERE UPPER(ReviewStatus)=N'PENDING'
                ORDER BY CandidateCount DESC,LastSeenAt DESC,NormalizedCommentHash,AiResult
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """,
        rs -> {
          total[0] = rs.getLong("TotalCount");
          items.add(
              new ReviewQueueModels.Item(
                  rs.getString("NormalizedCommentHash"),
                  rs.getInt("AiResult"),
                  rs.getString("SampleComment"),
                  rs.getString("NormalizedComment"),
                  rs.getInt("CandidateCount"),
                  instant(rs.getTimestamp("FirstSeenAt")),
                  instant(rs.getTimestamp("LastSeenAt")),
                  nullableLong(rs, "LastPlanResultId"),
                  nullableLong(rs, "LastEvidencePhotoId"),
                  rs.getString("SuggestedCategory"),
                  rs.getString("SuggestedMethod"),
                  nullableDouble(rs, "SuggestedConfidence"),
                  rs.getString("ReviewStatus")));
        },
        page * pageSize,
        pageSize);
    return new ReviewQueueModels.Page(List.copyOf(items), total[0], page, pageSize);
  }

  public ReviewQueueModels.Approval approve(
      String normalizedCommentHash,
      int aiResult,
      PromotableCategory category,
      String reviewedBy,
      String reviewNotes) {
    JdbcTemplate jdbc = registry.jdbc(TENANT);
    try {
      return jdbc.query(
          connection -> {
            var statement = connection.prepareStatement(approvalSql());
            statement.setString(1, normalizedCommentHash);
            statement.setInt(2, aiResult);
            statement.setString(3, category.name());
            statement.setString(4, reviewedBy);
            if (reviewNotes == null || reviewNotes.isBlank()) {
              statement.setNull(5, Types.NVARCHAR);
            } else {
              statement.setString(5, reviewNotes);
            }
            return statement;
          },
          rs -> {
            if (!rs.next()) {
              throw new ReviewQueueConflictException();
            }
            return new ReviewQueueModels.Approval(
                rs.getString("NormalizedCommentHash"),
                rs.getInt("AiResult"),
                rs.getString("ReviewStatus"),
                rs.getString("ReviewedResultCategory"),
                rs.getString("ReviewedBy"),
                instant(rs.getTimestamp("ReviewedAt")),
                rs.getBoolean("Idempotent"));
          });
    } catch (DataAccessException exception) {
      int errorNumber = sqlErrorNumber(exception);
      if (errorNumber == 50001) {
        throw new ReviewQueueNotFoundException();
      }
      if (errorNumber >= 50002 && errorNumber <= 50003) {
        throw new ReviewQueueConflictException();
      }
      throw exception;
    }
  }

  private String approvalSql() {
    return """
                DECLARE @NormalizedCommentHash CHAR(64)=?;
                DECLARE @AiResult TINYINT=?;
                DECLARE @ReviewedResultCategory NVARCHAR(100)=?;
                DECLARE @ReviewedBy NVARCHAR(255)=?;
                DECLARE @ReviewNotes NVARCHAR(1000)=?;
                SET NOCOUNT ON;
                SET XACT_ABORT ON;
                BEGIN TRANSACTION;
                DECLARE @CurrentStatus NVARCHAR(20);
                SELECT @CurrentStatus=UPPER(ReviewStatus)
                FROM ctl.SmartauditsAiCommentReviewQueue WITH (UPDLOCK,HOLDLOCK)
                WHERE NormalizedCommentHash=@NormalizedCommentHash AND AiResult=@AiResult;
                IF @CurrentStatus IS NULL
                    THROW 50001,N'No se encontro la fila de SmartAudits.',1;
                IF @CurrentStatus=N'APPROVED'
                BEGIN
                    IF EXISTS (SELECT 1 FROM ctl.SmartauditsAiCommentReviewQueue
                               WHERE NormalizedCommentHash=@NormalizedCommentHash AND AiResult=@AiResult
                                 AND ReviewedResultCategory=@ReviewedResultCategory)
                    BEGIN
                        SELECT q.NormalizedCommentHash,q.AiResult,q.ReviewStatus,q.ReviewedResultCategory,
                               q.ReviewedBy,q.ReviewedAt,CAST(1 AS bit) AS Idempotent
                        FROM ctl.SmartauditsAiCommentReviewQueue q
                        WHERE q.NormalizedCommentHash=@NormalizedCommentHash AND q.AiResult=@AiResult;
                        COMMIT TRANSACTION;
                        RETURN;
                    END;
                    THROW 50002,N'La fila ya fue aprobada con otro resultado.',1;
                END;
                IF @CurrentStatus<>N'PENDING'
                    THROW 50002,N'La fila no tiene un estado aprobable.',1;
                UPDATE ctl.SmartauditsAiCommentReviewQueue
                SET ReviewedResultCategory=@ReviewedResultCategory,ReviewedBy=@ReviewedBy,
                    ReviewedAt=SYSUTCDATETIME(),ReviewNotes=@ReviewNotes,ReviewStatus=N'APPROVED',
                    ModifiedAt=SYSUTCDATETIME()
                WHERE NormalizedCommentHash=@NormalizedCommentHash AND AiResult=@AiResult;
                IF @@ROWCOUNT<>1
                    THROW 50003,N'No fue posible finalizar la aprobacion.',1;
                SELECT q.NormalizedCommentHash,q.AiResult,q.ReviewStatus,q.ReviewedResultCategory,
                       q.ReviewedBy,q.ReviewedAt,CAST(0 AS bit) AS Idempotent
                FROM ctl.SmartauditsAiCommentReviewQueue q
                WHERE q.NormalizedCommentHash=@NormalizedCommentHash AND q.AiResult=@AiResult;
                COMMIT TRANSACTION;
                """;
  }

  private int sqlErrorNumber(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof SQLException sqlException && sqlException.getErrorCode() != 0) {
        return sqlException.getErrorCode();
      }
      current = current.getCause();
    }
    return 0;
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
  }

  private Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private Double nullableDouble(ResultSet rs, String column) throws SQLException {
    double value = rs.getDouble(column);
    return rs.wasNull() ? null : value;
  }
}
