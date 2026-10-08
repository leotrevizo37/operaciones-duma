# Arquitectura

## Limites de dominio

La solucion contiene tres verticales independientes. Cada vertical posee su dominio —sus consultas, sus tablas de hechos, su registro de respuesta—, su acceso de solo lectura a los warehouses, su configuracion por tenant, su regla de autorizacion y su suite de pruebas. Los tres se despliegan, fallan y se reinician por separado.

**Existe una libreria de runtime compartida, `operaciones-core`**, y contiene el armazon: la regla de cobertura junto a su enum, las propiedades de modulo, el servicio de system logs, el filtro de request id, el endpoint de telemetria, el del manifiesto, y la confianza en Duma Web —el JWKS, el decoder de JWT y el CORS. Cada modulo la declara como dependencia y anade `com.duma.core` a su `scanBasePackages`.

Lo que **no** sube a core es el `securityFilterChain`: es la unica parte de la seguridad de modulo donde la politica varia de verdad —smartaudits exige `SCOPE_observability:write` para aprobar, los otros tres no—, y por eso se queda en cada modulo. El reparto es deliberado: la validacion del token se escribe una vez, la decision de que puede hacer ese token se escribe donde se decide.

Hasta 2026-08-28 este parrafo afirmaba lo contrario —«no existe una libreria de runtime compartida entre modulos»— y lo justificaba «para que cada directorio pueda convertirse en repositorio independiente». **Ese fin dejo de sostenerse**: los cuatro se construyen con un solo reactor Maven, se despliegan con un solo script, y comparten fichero de entorno, host de warehouse, base de system logs y emisor de JWKS. Lo que si sostenia era el coste: **siete correcciones del armazon en noventa dias hubo que escribirlas tres o cuatro veces**, y las copias llegaron a divergir sin que ninguna prueba lo notara.

El acoplamiento **entre modulos** sigue siendo cero: ninguno depende de otro, y los contratos HTTP versionados siguen siendo la unica frontera con el consumidor. Lo que se comparte es el armazon, hacia abajo, no el dominio.

**Ninguna vertical sirve UI.** La sirve Duma Web, que es la unica puerta de entrada.

```text
Navegador
  |
  | sesion de Duma Web
  v
Duma Web (Next.js) --> BFF de Duma Web (ASP.NET)
                          |
                          | JWT RS256, aud por modulo, emisor `duma-web-internal`
                          |
                          +--> Experiencia API
                          +--> Lecturas API
                          +--> SmartAudits API

Cada backend --> SQL Server de system logs
Cada modulo  --> Warehouses SQL Server por tenant
```

## Frontera de integracion

Cada backend publica su manifiesto en `GET /api/module/manifest`, en la version `2.0` del protocolo. Es descripcion, no montaje: declara identidad, clasificacion y capacidades para que el consumidor sepa que esta leyendo y con que procedencia.

**La version subio a `2.0` al retirar el protocolo de Custom Elements.** Los campos `customElement` y `remoteEntryUrl` desaparecieron con el. Dejarla en `1.0` habria hecho que dos manifiestos con campos distintos afirmaran ser la misma version.

El acoplamiento entre Duma Web y estos backends es exclusivamente HTTP. No hay ESM remoto, ni Custom Elements, ni contexto de host, ni eventos DOM: eso pertenecia al portal retirado.

## Autenticacion y preparacion de permisos

**Duma Web autentica y emite.** Firma un JWT RS256 de vida corta con `aud` igual al identificador del modulo, y publica su JWKS. Cada backend valida firma, expiracion, emisor y audiencia contra `DUMA_AUTH_ISSUER` y `DUMA_AUTH_JWKS_URI`.

Ninguna de las dos variables tiene valor por defecto, y es deliberado: `NimbusJwtDecoder` rechaza un JWKS vacio, asi que un modulo que no las declare no arranca. Con respaldo, un despliegue que las olvidara seguiria confiando en quien estuviera escrito en el arbol — que es exactamente como una plataforma acaba fiandose del emisor equivocado sin que nadie lo note.

Los modulos no implementan login y el navegador nunca ve estos tokens: las llamadas son de servidor a servidor.

**La unica regla de autorizacion granular vive en `smartaudits`**: el `POST` de su cola de revision exige `SCOPE_observability:write`. El `GET` sobre la misma ruta y el resto de `/api/**` piden unicamente autenticacion; ensancharlo convertiria un cierre de riesgo acotado en una rotura de superficie.

En modo standalone los endpoints de lectura pueden habilitarse para desarrollo local. Ese modo aplica `permitAll` a todo y **cualquier prueba de seguridad hecha ahi es un falso verde**.

## Datos multi-tenant

El registro contiene siete tenants. Las consultas se ejecutan de forma aislada por tenant. El fallo de uno no invalida la respuesta de los demas. Antes de consultar, cada repositorio comprueba la existencia de los objetos requeridos.

| Estado | Significado | Tratamiento visual |
|---|---|---|
| `AVAILABLE` | Objetos presentes y filas en el periodo | KPIs y evidencia |
| `NO_DATA` | Objetos presentes sin filas | Estado vacio neutral |
| `NOT_SUPPORTED` | Objetos requeridos ausentes | Sin cobertura, neutral |
| `UNAVAILABLE` | Conexion o consulta fallida | Servicio no disponible, advertencia |

`NO_DATA` y `NOT_SUPPORTED` no son estados operativos buenos o malos. **Los cuatro estados son el producto del sistema, no metadatos**: cualquier normalizacion intermedia es un punto donde pueden colapsarse entre si, y por eso el consumidor recibe el cuerpo integro.

## System logs

Cada backend escribe eventos con su propio `application_id` en SQL Server. La configuracion separa el host de auditoria de los warehouses. Se registran request id, correlation id, actor, tenant, resultado, duracion, origen, user agent sanitizado y metadata JSON limitada. No se almacenan contrasenas, tokens, comentarios SmartAudits, cuerpos de respuesta ni datos de sensores.

Duma Web propaga el usuario que origino la llamada para que aterrice en el campo de actor. **Esta es la fuente unica para las lecturas**; duplicar el registro crearia dos historias que habria que reconciliar durante una investigacion.

Los eventos de request son asincronos. Las acciones auditables, como una promocion SmartAudits, se escriben de forma sincrona despues de confirmar la transaccion de negocio. Ese rastro es **por peticion y no por efecto** —un reintento idempotente deja dos filas para un solo efecto—, razon por la que el consumidor mantiene ademas su propia auditoria de la escritura.

## Lectura operativa de UI

Este repositorio ya no sirve UI. Se conservan aqui los criterios que aplicaban los frontends retirados, porque son el referente contra el que se verificaron por paridad las vistas que hoy sirve Duma Web.

Resumen ICOS: soporte de decision para directivo o gerente regional, densidad 2, proximidad de accion 1 y orientacion temporal 3. Abre con un veredicto BLUF y organiza el resumen por ICOS.

Experiencia y Lecturas: exploracion y triage para gerente de sitio u operador, densidad 6, accion 2 a 4 y orientacion temporal 5 a 7. SmartAudits combina exploracion con una superficie de accion humana: densidad 8 y accion 8 dentro de la cola.

Todas las vistas usan maximo tres severidades, timestamp real, filtros visibles, estados vacios y detalle inline o modal centrado. No usan gauges, graficas 3D, drawers laterales ni numeros sin baseline.
