# Contrato de integracion de modulo

## Version

El protocolo exige la version `2.0`. Cualquier version distinta bloquea el consumo hasta que exista una politica explicita de compatibilidad.

**`2.0` es la version posterior al retiro del portal.** La `1.0` describia un protocolo de Custom Elements: el host importaba un ESM remoto, esperaba el elemento, le asignaba un contexto con funciones y negociaba un handshake por eventos DOM. Nada de eso existe ya. Subir la version fue deliberado — dejarla en `1.0` habria hecho que dos manifiestos con conjuntos de campos distintos afirmaran ser la misma cosa.

El esquema vive en `contracts/module-manifest.schema.json`, y es el unico que queda: `module-host-context.schema.json` describia el contexto del host y se elimino con el protocolo.

## Manifiesto

Cada backend publica `GET /api/module/manifest`. Es la unica ruta de `/api/**` exenta de autenticacion junto a `/actuator/health`, porque el consumidor necesita conocer etapa y entorno antes de tener con que llamar.

Campos requeridos, los diez:

- `protocolVersion`
- `moduleId`
- `displayName`
- `apiBaseUrl`
- `releaseStage`
- `dataEnvironment`
- `freshnessMode`
- `clearance`
- `tenantScope`
- `capabilities`

Los campos `customElement` y `remoteEntryUrl` **salieron del contrato** al retirarse el protocolo que los usaba. No estan deprecados: no existen, y el esquema declara `additionalProperties: false`.

## Rutas de lectura

| Ruta | Autenticacion | Devuelve |
|---|---|---|
| `GET /api/module/manifest` | No | Identidad y clasificacion del modulo |
| `GET /actuator/health` | No | Sonda de arranque del contenedor |
| `GET /api/{modulo}` | **Si** | La lectura de salud, con cobertura explicita por tenant |
| `GET /api/{modulo}/freshness` | **Si** | Marcas de ultima carga |

`{modulo}` es `experiencia-digital`, `lecturas` o `smartaudits`.

## Token por modulo

**Lo emite Duma Web, no este repositorio.** Firma RS256 con rotacion de llaves y publica su JWKS; su identificador de emisor es `duma-web-internal`.

Forma del token: sujeto de identidad de servicio, `aud` igual al identificador del modulo, TTL corto, y un claim con el usuario que origino la llamada, solo para trazabilidad. **Sin `tenantId`**: la superficie es cross-tenant y un identificador inventado se leeria despues como alcance real.

Cada backend valida firma, emisor y audiencia. **Un token de un modulo no es aceptado por otro aunque la firma sea valida.** El emisor y el JWKS se configuran en `DUMA_AUTH_ISSUER` y `DUMA_AUTH_JWKS_URI`, ninguna con valor por defecto.

El selector de llaves y `JwtValidators.createDefaultWithIssuer(...)` aceptan **un solo valor cada uno**. Por eso la confianza no es aditiva: apuntar a un emisor invalida en el acto los tokens de cualquier otro. Convivir con dos exigiria un `DelegatingOAuth2TokenValidator` con dos decodificadores.

**El juego de llaves se renueva en segundo plano, antes de caducar.** Desde el 2026-08-27 el decodificador no se arma con `NimbusJwtDecoder.withJwkSetUri(...)`, que dejaba el refresco anticipado apagado: con esa forma, cada vez que la cache caducaba la siguiente validacion se bloqueaba pidiendo el JWKS de vuelta a Duma Web, y si esa peticion coincidia con el abanico del Resumen podia vencer y devolver 500. El TTL no cambio; cambio donde ocurre la renovacion.

## Escritura

Solo existe una, y es la cola de revision humana de `smartaudits`:

| Ruta | Exige |
|---|---|
| `GET /api/smartaudits/review-queue` | Autenticacion |
| `POST /api/smartaudits/review-queue/approve` | Autenticacion **y** `SCOPE_observability:write` |

El alcance solo lo exige el `POST`. El `GET` sobre la misma ruta y el resto de `/api/**` siguen pidiendo unicamente autenticacion: ensancharlo convertiria un cierre de riesgo acotado en una rotura de superficie.

En `ModuleSecurityConfig` el matcher del `POST` va **delante** del que no lleva verbo. Spring evalua en orden y la primera coincidencia decide; al reves, el alcance no se exigiria nunca.

## Correlacion

Toda peticion, de lectura o de escritura, acepta la cabecera `X-Request-Id`.

| Cara | Valor |
|---|---|
| Gramatica aceptada | `^[A-Za-z0-9._-]{1,64}$` |
| Quien lo origina | El servidor Next de Duma Web, con la forma `dw-<uuid>`. Si la peticion llega al BFF sin id, este acuña uno con la forma `bff-<uuid>` |
| Si no viene, o no casa | **Se descarta en silencio** y `RequestIdFilter` genera un UUID propio |
| Donde aterriza | La columna de request id de `audit.system_event`, y el MDC de cada linea de log JSON bajo la clave `requestId` |
| Respuesta | El mismo id efectivo vuelve en `X-Request-Id` |

**El descarte silencioso es la trampa de esta integracion, y por eso se documenta.** Un id que no
casa con la gramatica no produce error ni aviso: la peticion responde 200 y la fila queda con un id
que no corresponde a ninguna visita. El caso concreto que motivo escribirlo: el `TraceIdentifier`
por defecto de ASP.NET tiene la forma `0HNCS7B0M6BQ5:00000001`, y los dos puntos hacen fallar la
gramatica. Propagarlo tal cual parece funcionar y no correlaciona nada. Por eso el BFF normaliza
siempre, tenga o no id entrante.

El id se acepta por su forma, no por su procedencia. Es un identificador de correlacion: no autoriza
nada, no selecciona tenant y no entra en ninguna decision. La gramatica acotada esta para impedir
que un salto de linea o un caracter de control inyecte entradas falsas en el log, no para probar
quien llama.

**Un id por visita, no por peticion.** Las ~12 llamadas que Duma Web hace al pintar el tablero
comparten un solo id, que es lo que permite preguntar por una carga de pantalla completa en vez de
por una llamada suelta.

## Cobertura por tenant

Toda respuesta de lectura declara, por tenant, uno de cuatro estados:

| Estado | Significado |
|---|---|
| `AVAILABLE` | Objetos presentes y filas en el periodo |
| `NO_DATA` | Objetos presentes sin filas |
| `NOT_SUPPORTED` | Objetos requeridos ausentes |
| `UNAVAILABLE` | Conexion o consulta fallida |

**Son el producto del sistema, no metadatos.** Ninguno se representa como cero, y el consumidor debe reenviar el cuerpo integro: cualquier normalizacion intermedia es un punto donde estos estados pueden colapsarse entre si.
