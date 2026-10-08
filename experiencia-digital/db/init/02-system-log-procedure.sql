CREATE OR ALTER PROCEDURE audit.usp_RecordSystemEvent
    @ApplicationId nvarchar(80), @EventType nvarchar(80), @EventName nvarchar(300),
    @Outcome nvarchar(30), @Severity nvarchar(20), @RequestId nvarchar(64) = NULL,
    @ActorId nvarchar(255) = NULL, @TenantId nvarchar(80) = NULL, @DurationMs bigint = NULL,
    @SourceIp nvarchar(64) = NULL, @UserAgent nvarchar(512) = NULL, @MetadataJson nvarchar(4000) = NULL
AS
BEGIN
    SET NOCOUNT ON;
    INSERT INTO audit.system_event (
        application_id, event_type, event_name, outcome, severity, request_id,
        actor_id, tenant_id, duration_ms, source_ip, user_agent, metadata_json
    ) VALUES (
        @ApplicationId, @EventType, @EventName, @Outcome, @Severity, @RequestId,
        @ActorId, @TenantId, @DurationMs, @SourceIp, @UserAgent, @MetadataJson
    );
END;
GO
