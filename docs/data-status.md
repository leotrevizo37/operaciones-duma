# Estado, origen y alcance del dato

Cada modulo muestra un grupo de badges persistente. Las dimensiones no se combinan para evitar que "modulo en pruebas" se interprete como "datos de prueba" o que "datos de produccion" se interprete como "modulo liberado".

## Etapa de liberacion

- `DEVELOPMENT`: implementacion activa; badge rojo.
- `TESTING`: validacion funcional; badge amarillo.
- `STAGING`: candidata previa a liberacion; badge amarillo.
- `PRODUCTION`: modulo liberado; badge verde.

## Entorno del dato

- `DEVELOPMENT`: datos de desarrollo; badge rojo.
- `TEST`: datos de prueba; badge rojo.
- `STAGE`: datos preproductivos; badge amarillo.
- `PRODUCTION`: datos productivos; badge verde.

## Frescura

- `LIVE`: consulta la fuente configurada y exige timestamp de ultima actualizacion.
- `SNAPSHOT`: usa un corte inmutable y exige mostrar su fecha de corte.
- `MOCK`: usa datos sinteticos y nunca puede recibir tratamiento visual de produccion.

La frescura se muestra en el contexto general mediante el timestamp `Actualizado`; no se repite como badge dentro de cada modulo.

## Clearance y alcance

La clasificacion inicial es `ACADEMIC_PRIVATE`. El alcance declara `ALL_TENANTS`, `SELECTED_TENANTS` o `CARLSJR_ONLY`.

Cada modulo se configura con el prefijo `DUMA_EXPERIENCE`, `DUMA_READINGS` o `DUMA_SMARTAUDITS` y los sufijos `RELEASE_STAGE`, `DATA_ENVIRONMENT`, `FRESHNESS_MODE`, `CLEARANCE` y `TENANT_SCOPE`. `config/runtime.example.ps1` contiene la matriz completa sin valores privados.

Los badges son informacion y no una frontera de seguridad. La autorizacion se valida en el backend aunque el consumidor oculte una accion: una UI que esconde un boton no impide la llamada.
