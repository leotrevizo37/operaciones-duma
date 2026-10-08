# Contrato de batería y diagnóstico de conectividad de Lecturas

Actualización: 2026-10-07. Documento privado para aplicaciones que consumen el backend de Lecturas como microservicio.

## Propósito y alcance

La actualización obtiene la batería del agregado que ya ofrece el warehouse y añade evidencia MQTT para interpretar radio, recepciones por gateway y tipos de error. Conserva las rutas existentes, los campos del dashboard, la selección de tenants configurados y los mecanismos de autenticación del módulo. No modifica tablas, vistas, ETL, configuración privada ni dependencias.

Referencia de datos: `ESPECIFICACION_CONSUMO_DATOS_SENSE.md`, revisión local de 2026-10-07. Esa especificación describe contratos; no certifica que todas las bases desplegadas tengan las columnas nuevas o datos históricos completos.

## Solicitudes y compatibilidad

El dashboard conserva `GET /api/readings?tenant=emerson&from=2026-10-01&to=2026-10-07`. Las fechas son inclusivas en la selección existente. Se conserva el límite de 367 días y los errores de validación existentes. La respuesta contiene `generatedAt`, `from`, `to` y `tenants`.

Se incorpora el parámetro booleano opcional `diagnostics`, con valor predeterminado `false`:

```http
GET /api/readings?tenant=emerson&from=2026-10-01&to=2026-10-07&diagnostics=true
```

Cada tenant incorpora `diagnostics`, un array vacío cuando no se solicitó detalle o no existen grupos recuperables. El consumidor debe admitir campos nuevos y opcionales. Los contratos anteriores de `timeline`, `sensors`, `current`, `previous`, `hourly` y `exceptions` se conservan. Los fallbacks a auditorías y mediciones mantienen su comportamiento y no incorporan estos diagnósticos.

El frontend realiza primero la consulta sin diagnósticos. Solicita el detalle cuando se selecciona «Señal», «Uplinks y gateway» o una celda. Reutiliza el resultado dentro del mismo contexto de filtros/periodo/tenants; un contexto nuevo vuelve a consultarlo. La consulta adicional no sustituye los datos básicos si falla: muestra el fallo del diagnóstico y conserva el dashboard básico. La carga inicial y la batería no pagan el coste de las consultas adicionales.

## Batería: cambio de fuente

La resolución existente depende de `DAYS.between(from,to)`: menos de 8 selecciona horas, menos de 42 selecciona días y 42 o más selecciona semanas. Se conserva esa frontera, no se redefine el intervalo.

| Resolución | Fuente de `timeline.batteryLevel` |
|---|---|
| Hora | `serving.vwSenseSensorCommunicationHealthHourly.BatteryLevel` |
| Día | `serving.vwSenseSensorCommunicationHealthDaily.BatteryLevel` |
| Semana | `serving.vwSenseSensorCommunicationHealthWeekly.BatteryLevel` |

Se elimina el fallback `OUTER APPLY` que buscaba una observación horaria para cada sensor-periodo. Esto elimina esa consulta correlacionada y conserva el valor elegido por el contrato del warehouse. No representa una medición de mejora de latencia en una base operativa.

Las vistas diarias y semanales exponen `BatteryLevel`, pero no `ExternalPowerSource`, `BatteryLevelSource` ni `BatteryObservedAt`. El backend conserva esos campos con `null` cuando la columna no existe. Tampoco reconstruye batería desde horas si la vista antigua no tiene `BatteryLevel`: devuelve `null`. Un consumidor que dependía de ese fallback debe tener las vistas actualizadas para recuperar batería agregada.

El valor diario/semanal es una observación seleccionada por el ETL, no un promedio de porcentajes ni la batería al inicio del periodo. No reconstruir su fecha a partir del periodo. El frontend conserva el tratamiento existente de valores cero y la inferencia horaria cercana; elimina la inferencia «no usa batería» basada únicamente en varios registros sin batería. La ausencia de batería o de alimentación externa permanece desconocida.

## Estructura del diagnóstico

El array `tenants[].diagnostics` contiene grupos por sensor, periodo local y fuente:

```json
{
  "sensorId": "11111111-1111-1111-1111-111111111111",
  "localPeriodStart": "2026-10-01T00:00:00",
  "source": "observability.factChirpStackDeviceEventMetrics",
  "hours": [
    {
      "timeSpan": "2026-10-01T06:00:00Z",
      "devEui": "0000000000000001",
      "capturedUplinkCount": 2,
      "observedGatewayCount": 2,
      "multiGatewayUplinkCount": 1,
      "eventLogCounts": { "INFO": 1 },
      "radioProfileCounts": null,
      "radioFieldQuality": null,
      "extractedAt": "2026-10-01T07:05:00Z"
    }
  ]
}
```

El ejemplo es ficticio. `localPeriodStart` se une con `timeline.localTimeSpan` y `sensorId`, dentro del mismo tenant. Es una etiqueta local sin offset. `hours[].timeSpan` conserva la hora UTC, incluidos los instantes distintos que comparten una hora local durante un cambio de horario. Los timestamps de extracción se serializan en UTC con `Z`.

En días se agrupan las horas por fecha local; en semanas por lunes local. La consulta semanal incluye los bordes completos de las semanas seleccionadas por las vistas existentes. No interpretar una semana como un intervalo recortado exactamente a las fechas solicitadas. Las uniones a los hechos usan `DevEui` y `TimeSpan` UTC de la vista horaria, nunca solo la fecha o la hora local.

Los datos físicos se presentan por sensor para asociarlos con el mapa existente. Si varios sensores comparten un DevEUI, pueden repetir la misma evidencia física. Para totales físicos entre sensores, deduplicar eventos por `(devEui,timeSpan)` y recepciones por `(devEui,gatewayId,timeSpan)`. No sumar todo `diagnostics` como si fueran poblaciones independientes.

## Campos según fuente

Todos los registros incluyen `timeSpan` y `devEui`. Las columnas adicionales se seleccionan solo si existen en la base. Una propiedad ausente indica columna no disponible; una propiedad `null` conserva SQL NULL. Ninguna de las dos equivale a cero ni a JSON vacío.

| `source` | Campos adicionales de cada elemento de `hours` |
|---|---|
| `observability.factChirpStackDeviceEventMetrics` | `capturedUplinkCount`, `observedGatewayCount`, `multiGatewayUplinkCount`, `eventLogCounts`, `radioProfileCounts`, `radioFieldQuality`, `extractedAt` |
| `observability.factChirpStackDeviceGatewayMetrics` | `gatewayId`, `gatewayReceptionCount`, `receptionShare`, `averageRssi`, `minRssi`, `maxRssi`, `averageSnr`, `minSnr`, `maxSnr`, `radioReceptionStats`, `receptionFieldQuality`, `extractedAt` |
| `observability.factChirpStackDeviceMetrics` | `metricsAvailable`, `linkErrorCount`, `linkErrorCounts`, `extractedAt` |
| `observability.factChirpStackGatewayMetrics` | `gatewayId`, `metricsAvailable`, `txOkCount`, `txErrorCount`, `txPacketStatusCounts`, `extractedAt` |

Las columnas SQL con sufijo `Json` se deserializan y se exponen con nombre camelCase sin ese sufijo. Son objetos/arrays JSON, no cadenas de JSON. El backend valida sintaxis y forma superior: perfiles/estadísticas deben ser arrays; desgloses y calidad deben ser objetos. Esa validación no certifica todas las reglas semánticas del ETL dentro de cada elemento.

`radioReceptionStats` conserva los campos de cada perfil: `regionConfigId`, `adr`, `dr`, `spreadingFactor`, `bandwidthHz`, `frequencyHz`, `channel`, `rfChain`, `receptionCount`, `rssiCount`, `rssiSum`, `rssiMin`, `rssiMax`, `snrCount`, `snrSum`, `snrMin`, `snrMax`. Las sumas y extremos decimales conservan las cadenas producidas por el warehouse. Convertirlas explícitamente y rechazar valores no finitos antes de operar. Frecuencia y ancho de banda se expresan en Hz; RSSI en dBm y SNR en dB. Canal cero y cadena RF cero son valores válidos.

`radioProfileCounts` describe perfiles de uplinks, mientras `radioReceptionStats` describe recepciones por gateway. No unir ambos arrays como si cada elemento fuera un evento individual. `radioFieldQuality` y `receptionFieldQuality` conservan por campo sus conteos `valid`, `missing` e `invalid`.

## Conteos y agregaciones correctas

- `capturedUplinkCount` cuenta uplinks válidos del historial MQTT procesado; no reemplaza `timeline.receivedUplinkCount` de ChirpStack ni prueba todos los intentos físicos de transmisión.
- `multiGatewayUplinkCount` se suma desde los eventos, una vez por dispositivo-hora. Los eventos no se cruzan con las filas de gateways en la respuesta.
- `gatewayReceptionCount` se suma como recepciones: un uplink recibido por dos gateways puede aportar dos recepciones. No llamarlo uplinks únicos.
- `receptionShare` es una fracción entre 0 y 1 almacenada por dispositivo-gateway-hora; multiplicar por 100 solo para presentación. No sumar las participaciones de varios gateways como cobertura total.
- `linkErrorCounts`, `eventLogCounts` y `txPacketStatusCounts` son objetos de etiquetas dinámicas y conteos. Mantenerlos separados: los logs no son necesariamente errores y los estados TX incluyen `OK`.
- Los estados TX se consultan únicamente para gateways observados, unidos por gateway, hora UTC y tenant de red. Son actividad completa del gateway, no errores causados exclusivamente por el dispositivo seleccionado. Si se agregan entre sensores, deduplicar por `(gatewayId,timeSpan)`.
- `{}` significa desglose disponible sin conteos positivos. SQL NULL/propiedad ausente significa desglose no disponible. El panel muestra cuántos registros tienen desglose respecto de los recuperados.

Para señal ponderada usar `SUM(rssiSum)/SUM(rssiCount)` y `SUM(snrSum)/SUM(snrCount)`, convirtiendo las cadenas. Cada magnitud tiene su propio denominador. No ponderar una media con todas las recepciones ni incluir recepciones de filas sin detalle en ese denominador.

## Interpretación y colores de señal

La fórmula anterior combinaba RSSI y SNR con umbrales fijos, independientes de SF. Ahora el mapa y la serie de señal usan una referencia de margen SNR calculada dentro de cada grupo de `radioReceptionStats`, donde señal y perfil pertenecen a la misma población.

La referencia se limita a SF7–SF12, anchos de banda 125/250/500 kHz y frecuencias sub-GHz entre 100 MHz y menos de 1 GHz. No se inventan umbrales para SF5/SF6, otras bandas o perfiles incompletos. DR se presenta con la región, SF y ancho de banda observados; no se convierte universalmente a SF porque esa correspondencia depende de la región.

Para cada perfil con muestras SNR válidas:

```text
SNR de referencia = -7.5 - 2.5 × (SF - 7) dB
SNR medio = snrSum / snrCount
margen = SNR medio - SNR de referencia
score del perfil = clamp((margen + 5) / 15 × 100, 0, 100)
score del periodo = SUM(score del perfil × snrCount) / SUM(snrCount evaluables)
```

La referencia de SF se apoya en la mejora aproximada de sensibilidad de 2.5 dB por incremento de SF y la demodulación cercana a −20 dB en SF12 descritas por [Semtech](https://www.semtech.com/design-support/faq/faq-lora). La escala de visualización y sus categorías son decisiones de esta aplicación, no umbrales certificados del hardware o del warehouse.

Se etiqueta crítica para score menor de 33⅓, degradada entre 33⅓ y menos de 73⅓, y buena desde 73⅓. Se conserva el degradado verde existente; gris representa falta de perfil evaluable. El score es una referencia visual, no un porcentaje de éxito, pérdida o disponibilidad. Un promedio puede ocultar variabilidad dentro de un perfil.

RSSI se muestra como evidencia física y no determina por sí solo el color: faltan modelo de receptor y una sensibilidad calibrada. El panel muestra medias independientes RSSI/SNR, sus muestras válidas, las recepciones evaluables frente a las observadas y la distribución de SF/DR/BW/frecuencia/canal/RF/región/ADR. En tooltips, los promedios MQTT disponibles se identifican como MQTT; si faltan, se conservan los promedios existentes sin asignarles arbitrariamente un perfil.

## Disponibilidad, fallos y coste

Las consultas usan el datasource existente del tenant y parámetros de fecha JDBC. No reciben nombres libres de bases o tablas del consumidor. Se mantienen los controles existentes; esta actualización no añade un modelo nuevo de permisos de observabilidad.

Si una fuente opcional no existe o no tiene permiso SELECT, se añade a `missingSources` y se conserva el dashboard disponible. Si falta la vista horaria o su `DevEui`, no se recuperan diagnósticos. Las columnas nuevas ausentes no invalidan fuentes antiguas. Si una fuente legible falla al consultarse o contiene JSON malformado/forma incompatible, la petición con diagnóstico devuelve el tenant `UNAVAILABLE` con `TENANT_QUERY_FAILED`, sin transformar el fallo en datos vacíos exitosos. Otros tenants conservan su resultado independiente.

Se realizan hasta cuatro consultas de detalle por tenant, una por fuente, acotadas por las fechas del periodo local. No se lanza una consulta por celda ni un `OUTER APPLY` de batería. El detalle conserva horas y puede aumentar considerablemente el payload en periodos largos y con muchos sensores/gateways; por eso es opcional y se carga bajo demanda. No se incorpora paginación ni se certifica su coste con volúmenes operativos.

Las consultas de fuentes se ejecutan secuencialmente: no garantizan un snapshot transaccional único entre ellas si el ETL actualiza durante la petición. Conservar `extractedAt` por registro para valorar antigüedad; la última extracción que muestra el panel no certifica frescura de todas las horas ni captura completa. La clasificación usa las estadísticas del mismo registro/perfil y no divide recepciones por un contador recuperado desde otra consulta.

## Validación de esta actualización

Las pruebas focalizadas cubren ponderación por muestras independientes, clasificación por SF, perfiles no soportados, datos ausentes, etiquetas dinámicas, batería diaria desconocida y el panel accesible con vista móvil. La prueba de integración usa un SQL Server temporal con datos ficticios: verifica batería diaria/semanal directa, conservación de UTC, agrupación por lunes local, ausencia de duplicación de uplinks con varios gateways, compatibilidad sin diagnóstico, fuente opcional ausente y JSON inválido.

Estas pruebas no demuestran tiempos de respuesta, población histórica, permisos ni despliegue en warehouses operativos. No se ejecutan migraciones ni escrituras en las bases de los tenants.
