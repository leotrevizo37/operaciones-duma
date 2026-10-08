CREATE OR ALTER VIEW serving.vwSenseSensors
AS
SELECT
    SensorId,
    PhysicalIdentifier,
    sensor_name AS SensorName,
    sensor_type AS SensorType,
    model_name AS ModelName,
    hub_model_name AS HubModelName,
    hub_name AS HubName,
    device_name AS DeviceName,
    device_type AS DeviceType,
    sublocation_name AS SubLocationName,
    sublocation_type AS SubLocationType,
    location_name AS LocationName,
    city_name AS CityName
FROM dwh.dimSidonProdDimensions
WHERE Active = 1;
GO

CREATE OR ALTER VIEW serving.vwSenseSensorCommunicationHealthDaily
AS
SELECT
    SensorId,
    DevEui,
    CONVERT(DATE, LocalTimeSpan) AS LocalDate,
    SUM(CONVERT(BIGINT, ReadingsCount)) AS ReadingsCount,
    SUM(ReceivedUplinkCount) AS ReceivedUplinkCount,
    SUM(ExpectedUplinkCount) AS ExpectedUplinkCount,
    SUM(DataCoveragePercentage) / NULLIF(COUNT_BIG(*), 0) AS DataCoveragePercentage,
    CASE WHEN SUM(ExpectedUplinkCount) > 0 THEN CONVERT(DECIMAL(18, 6), SUM(ReceivedUplinkCount) * CONVERT(DECIMAL(18, 6), 100) / NULLIF(SUM(ExpectedUplinkCount), 0)) END AS ChirpStackReceiptCoveragePercentage,
    CASE WHEN SUM(ReceivedUplinkCount) > 0 THEN CONVERT(DECIMAL(18, 6), SUM(CONVERT(BIGINT, ReadingsCount)) * CONVERT(DECIMAL(18, 6), 1) / NULLIF(SUM(ReceivedUplinkCount), 0)) END AS DataReadingsPerReceivedUplink,
    SUM(CONVERT(INT, CASE WHEN ReadingsCount > 0 THEN 1 ELSE 0 END)) AS HoursWithData,
    SUM(CONVERT(INT, CASE WHEN ReceivedUplinkCount > 0 THEN 1 ELSE 0 END)) AS HoursWithChirpStackUplinks,
    SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'DATA_INGESTION_PROBLEM' THEN 1 ELSE 0 END)) AS DataIngestionProblemHours,
    SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'DEVICE_NOT_COMMUNICATING' THEN 1 ELSE 0 END)) AS DeviceNotCommunicatingHours,
    SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'GATEWAY_PROBLEM' THEN 1 ELSE 0 END)) AS GatewayProblemHours,
    SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'UNKNOWN' THEN 1 ELSE 0 END)) AS UnknownHours,
    SUM(AverageRssi * ReceivedUplinkCount) / NULLIF(SUM(CASE WHEN AverageRssi IS NOT NULL THEN ReceivedUplinkCount END), 0) AS AverageRssi,
    SUM(AverageSnr * ReceivedUplinkCount) / NULLIF(SUM(CASE WHEN AverageSnr IS NOT NULL THEN ReceivedUplinkCount END), 0) AS AverageSnr,
    SUM(LinkErrorCount) AS LinkErrorCount,
    SUM(GatewayReceivedPacketCount) AS GatewayReceivedPacketCount,
    SUM(GatewayTransmittedPacketCount) AS GatewayTransmittedPacketCount,
    SUM(GatewayTxOkCount) AS GatewayTxOkCount,
    SUM(GatewayTxErrorCount) AS GatewayTxErrorCount,
    MAX(LatestGatewaySeenAt) AS LatestGatewaySeenAt,
    CONVERT(BIT, MAX(CONVERT(INT, HasLateReadings))) AS HasLateReadings,
    CONVERT(BIT, CASE WHEN SUM(CONVERT(BIGINT, ReadingsCount)) = 0 THEN 1 ELSE 0 END) AS IsConnectionLost,
    MAX(LastReadingAt) AS LastReadingAt,
    CASE
        WHEN SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'DATA_INGESTION_PROBLEM' THEN 1 ELSE 0 END)) > 0 THEN N'DATA_INGESTION_PROBLEM'
        WHEN SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'GATEWAY_PROBLEM' THEN 1 ELSE 0 END)) > 0 THEN N'GATEWAY_PROBLEM'
        WHEN SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'DEVICE_NOT_COMMUNICATING' THEN 1 ELSE 0 END)) > 0 THEN N'DEVICE_NOT_COMMUNICATING'
        WHEN SUM(CONVERT(BIGINT, ReadingsCount)) > 0 THEN N'OK'
        ELSE N'UNKNOWN'
    END AS CommunicationStatus
FROM serving.vwSenseSensorCommunicationHealthHourly
GROUP BY SensorId, DevEui, CONVERT(DATE, LocalTimeSpan);
GO

CREATE OR ALTER VIEW serving.vwSenseSensorCommunicationHealthWeekly
AS
SELECT
    SensorId,
    DevEui,
    DATEADD(DAY, -(DATEDIFF(DAY, CONVERT(DATE, '19000101', 112), LocalTimeSpan) % 7), CONVERT(DATE, LocalTimeSpan)) AS WeekStartDate,
    DATEADD(DAY, 6 - (DATEDIFF(DAY, CONVERT(DATE, '19000101', 112), LocalTimeSpan) % 7), CONVERT(DATE, LocalTimeSpan)) AS WeekEndDate,
    SUM(CONVERT(BIGINT, ReadingsCount)) AS ReadingsCount,
    SUM(ReceivedUplinkCount) AS ReceivedUplinkCount,
    SUM(ExpectedUplinkCount) AS ExpectedUplinkCount,
    SUM(DataCoveragePercentage) / NULLIF(COUNT_BIG(*), 0) AS DataCoveragePercentage,
    CASE WHEN SUM(ExpectedUplinkCount) > 0 THEN CONVERT(DECIMAL(18, 6), SUM(ReceivedUplinkCount) * CONVERT(DECIMAL(18, 6), 100) / NULLIF(SUM(ExpectedUplinkCount), 0)) END AS ChirpStackReceiptCoveragePercentage,
    CASE WHEN SUM(ReceivedUplinkCount) > 0 THEN CONVERT(DECIMAL(18, 6), SUM(CONVERT(BIGINT, ReadingsCount)) * CONVERT(DECIMAL(18, 6), 1) / NULLIF(SUM(ReceivedUplinkCount), 0)) END AS DataReadingsPerReceivedUplink,
    SUM(CONVERT(INT, CASE WHEN ReadingsCount > 0 THEN 1 ELSE 0 END)) AS HoursWithData,
    SUM(CONVERT(INT, CASE WHEN ReceivedUplinkCount > 0 THEN 1 ELSE 0 END)) AS HoursWithChirpStackUplinks,
    SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'DATA_INGESTION_PROBLEM' THEN 1 ELSE 0 END)) AS DataIngestionProblemHours,
    SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'DEVICE_NOT_COMMUNICATING' THEN 1 ELSE 0 END)) AS DeviceNotCommunicatingHours,
    SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'GATEWAY_PROBLEM' THEN 1 ELSE 0 END)) AS GatewayProblemHours,
    SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'UNKNOWN' THEN 1 ELSE 0 END)) AS UnknownHours,
    SUM(AverageRssi * ReceivedUplinkCount) / NULLIF(SUM(CASE WHEN AverageRssi IS NOT NULL THEN ReceivedUplinkCount END), 0) AS AverageRssi,
    SUM(AverageSnr * ReceivedUplinkCount) / NULLIF(SUM(CASE WHEN AverageSnr IS NOT NULL THEN ReceivedUplinkCount END), 0) AS AverageSnr,
    SUM(LinkErrorCount) AS LinkErrorCount,
    SUM(GatewayReceivedPacketCount) AS GatewayReceivedPacketCount,
    SUM(GatewayTransmittedPacketCount) AS GatewayTransmittedPacketCount,
    SUM(GatewayTxOkCount) AS GatewayTxOkCount,
    SUM(GatewayTxErrorCount) AS GatewayTxErrorCount,
    MAX(LatestGatewaySeenAt) AS LatestGatewaySeenAt,
    CONVERT(BIT, MAX(CONVERT(INT, HasLateReadings))) AS HasLateReadings,
    CONVERT(BIT, CASE WHEN SUM(CONVERT(BIGINT, ReadingsCount)) = 0 THEN 1 ELSE 0 END) AS IsConnectionLost,
    MAX(LastReadingAt) AS LastReadingAt,
    CASE
        WHEN SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'DATA_INGESTION_PROBLEM' THEN 1 ELSE 0 END)) > 0 THEN N'DATA_INGESTION_PROBLEM'
        WHEN SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'GATEWAY_PROBLEM' THEN 1 ELSE 0 END)) > 0 THEN N'GATEWAY_PROBLEM'
        WHEN SUM(CONVERT(INT, CASE WHEN CommunicationStatus = N'DEVICE_NOT_COMMUNICATING' THEN 1 ELSE 0 END)) > 0 THEN N'DEVICE_NOT_COMMUNICATING'
        WHEN SUM(CONVERT(BIGINT, ReadingsCount)) > 0 THEN N'OK'
        ELSE N'UNKNOWN'
    END AS CommunicationStatus
FROM serving.vwSenseSensorCommunicationHealthHourly
GROUP BY
    SensorId,
    DevEui,
    DATEADD(DAY, -(DATEDIFF(DAY, CONVERT(DATE, '19000101', 112), LocalTimeSpan) % 7), CONVERT(DATE, LocalTimeSpan)),
    DATEADD(DAY, 6 - (DATEDIFF(DAY, CONVERT(DATE, '19000101', 112), LocalTimeSpan) % 7), CONVERT(DATE, LocalTimeSpan));
GO
