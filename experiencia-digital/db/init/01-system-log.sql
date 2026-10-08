IF
    NOT EXISTS (
        SELECT 1 FROM sys.schemas
        WHERE name = N'audit'
    )
    BEGIN
        EXEC (N'CREATE SCHEMA audit');
    END;
GO

IF OBJECT_ID(N'audit.system_event', N'U') IS NULL
    BEGIN
        CREATE TABLE audit.system_event (
            event_id bigint IDENTITY (1, 1) NOT NULL CONSTRAINT PK_audit_system_event PRIMARY KEY,
            application_id nvarchar(80) NOT NULL,
            event_type nvarchar(80) NOT NULL,
            event_name nvarchar(300) NOT NULL,
            outcome nvarchar(30) NOT NULL,
            severity nvarchar(20) NOT NULL,
            request_id nvarchar(64) NULL,
            actor_id nvarchar(255) NULL,
            tenant_id nvarchar(80) NULL,
            duration_ms bigint NULL,
            source_ip nvarchar(64) NULL,
            user_agent nvarchar(512) NULL,
            metadata_json nvarchar(4000) NULL,
            created_at datetime2(3) NOT NULL CONSTRAINT DF_audit_system_event_created_at DEFAULT SYSUTCDATETIME()
        );
    END;
GO
