# Runbook detallado: configuracion, arranque y artifacts

## 1. Que ejecuta cada modo

Hay tres operaciones distintas:

| Operacion | Comando principal | Resultado |
|---|---|---|
| Construir y probar | `scripts\build-artifacts.ps1` | Tres JARs ejecutables y un manifiesto SHA-256 |
| Desarrollo | `scripts\start-environment.ps1 -Mode Development` | Tres backends Java 17 en Docker, con perfil `dev` |
| Ejecutar artifacts | `scripts\start-environment.ps1 -Mode Artifacts` | Tres JARs en Java 17, con la autenticacion real |

Desde el retiro del portal el 2026-08-26, **ningun modo levanta procesos Vite ni ocupa los puertos `5173` a `5177`**. Este repositorio no sirve UI; el punto de entrada integrado es Duma Web, y los tres backends siguen siendo ejecutables y consultables por separado. `config\ports.example.ps1` ya solo declara los tres puertos de backend, y el default de CORS de cada modulo quedo vacio: el unico consumidor es el BFF de Duma Web, servidor a servidor.

> **La diferencia entre los dos modos es de seguridad, no de comodidad.** `-Mode Development` anade `--spring.profiles.active=dev`, cuyo `application-dev.yml` aplica `permitAll` a todo. **`-Mode Artifacts` es el unico donde la autenticacion corre de verdad**, y por tanto el unico donde una comprobacion de seguridad significa algo.

## 2. Donde va cada configuracion

La configuracion local esta separada en dos archivos ignorados por Git:

| Archivo | Contenido permitido | Contenido prohibido |
|---|---|---|
| `config\ports.local.ps1` | Los tres puertos | Passwords, tokens o llaves |
| `config\runtime.local.ps1` | Hosts, nombres de bases, usuarios, badges, emisor y JWKS | Passwords o tokens |

Las plantillas versionadas son `config\ports.example.ps1` y `config\runtime.example.ps1`.

`runtime.local.ps1` **debe declarar `DUMA_AUTH_ISSUER` y `DUMA_AUTH_JWKS_URI`**. `start-environment.ps1` las exige y falla si faltan: antes las derivaba del puerto del shell y pisaba lo que hubiera, asi que cambiar la configuracion no tenia ningun efecto observable.

Los secretos no van en un archivo del repo. Los scripts los solicitan mediante un prompt seguro y los conservan solamente en memoria durante el arranque:

- password de SQL Server para `DumaSystemLogs`;
- password de SQL Server para los warehouses.

No se debe crear un `.env`. Tampoco se debe escribir el password en el comando, en `runtime.local.ps1`, en `application.yml` ni en un log.

## 3. Configuracion local actual

Desde una terminal PowerShell, en la raiz del checkout:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
notepad .\config\ports.local.ps1
notepad .\config\runtime.local.ps1
```

`Set-ExecutionPolicy -Scope Process Bypass` afecta unicamente esa terminal y no cambia la politica del equipo.

### 3.1 Puertos

| Aplicacion | Backend |
|---|---:|
| Experiencia y disponibilidad | 8081 |
| Lecturas | 8082 |
| SmartAudits | 8084 |

Para cambiar un puerto, editar unicamente su valor en `config\ports.local.ps1`. El script valida rango, duplicados y puertos ocupados antes de iniciar.

### 3.2 SQL Server y bases por tenant

`config\runtime.local.ps1` contiene host, puerto, usuario y nombres de bases, pero nunca el password.

Las variables de tenant son:

| Tenant | Variable de base |
|---|---|
| Carls Jr | `DUMA_TENANT_CARLSJR_DATABASE` |
| Emerson | `DUMA_TENANT_EMERSON_DATABASE` |
| Valle del Encino | `DUMA_TENANT_VALLEDELENCINO_DATABASE` |
| McDonalds | `DUMA_TENANT_MCDONALDS_DATABASE` |
| McDonalds CDP | `DUMA_TENANT_MCDONALDS_CDP_DATABASE` |
| SmartFit | `DUMA_TENANT_SMARTFIT_DATABASE` |
| Bafar POC gabinete | `DUMA_TENANT_BAFAR_POC_GABINETE_DATABASE` |

Los tres nombres conocidos ya estan definidos. Los otros cuatro permanecen vacios hasta conocer su nombre real. Una variable vacia no detiene el sistema: ese tenant se informa como `UNAVAILABLE` en el modulo correspondiente. No se debe inventar un nombre para evitar ese estado.

## 4. Prerrequisitos locales

Comprobar una sola vez:

```powershell
docker version
sqlcmd -?
```

El host no necesita Maven, Java 17, Node ni npm instalados. Los scripts usan:

- `maven:3.9.14-eclipse-temurin-17` para compilar y probar;
- `eclipse-temurin:17-jre` para ejecutar cada JAR;
- Docker Desktop para Testcontainers y los backends locales.

Node y npm dejaron de ser prerrequisitos al retirarse los frontends.

## 5. Inicializar DumaSystemLogs

Ejecutar una vez por base nueva, o nuevamente cuando se agregue un script idempotente:

```powershell
.\scripts\initialize-databases.ps1
```

El prompt pide la credencial SQL configurada por `DUMA_SYSTEMLOG_MSSQL_USERNAME`. El password se escribe en la ventana segura; no aparece en el historial.

El script:

1. conecta al host y puerto de `config\runtime.local.ps1`;
2. crea `DumaSystemLogs` si todavia no existe;
3. aplica los **seis** scripts versionados de `db\init`, dos por modulo;
4. deja evidencia sin secretos en `.runtime\logs\<fecha>-database-init.log`;
5. elimina la variable temporal `SQLCMDPASSWORD` al terminar.

Eran diez hasta el retiro del portal. **`audit.system_event` se sigue creando**: los tres modulos la crean idempotentemente sobre la misma base, asi que perder los dos scripts del portal no la deja sin creador.

Requiere que esa identidad tenga permiso para crear la base en la primera corrida y para crear o alterar esquemas, tablas y procedimientos. En corridas posteriores los scripts son idempotentes.

## 6. Identidad

**No hay ningun usuario que crear en este repositorio.** `security.app_user` y `provision-shell-user.ps1` se retiraron con el portal: quien autentica personas es Duma Web, contra su propio catalogo. Un administrador interno necesita una cuenta alli con `platform.admin.access`, no una aqui.

## 7. Construir artifacts reproducibles

```powershell
.\scripts\build-artifacts.ps1
```

El script perdio su parametro `-Install`, que existia solo para el `npm ci` de los frontends.

Ejecuta `mvn verify` de los tres backends con Java 17 y luego genera:

```text
artifacts/
  manifest.json
  experiencia-digital/experiencia-digital.jar
  lecturas/lecturas.jar
  smartaudits/smartaudits.jar
```

`manifest.json` registra nombre, tamano y SHA-256. `artifacts\` esta ignorado por Git y no se publica automaticamente.

## 8. Levantar todo para desarrollo

```powershell
.\scripts\start-environment.ps1 -Mode Development
```

Con la configuracion local actual se solicita una sola credencial SQL porque system logs y warehouses usan el mismo host y usuario. El script inicializa la base, levanta tres contenedores Java 17 y espera sus health checks.

URLs por defecto:

- experiencia: `http://localhost:8081`;
- lecturas: `http://localhost:8082`;
- SmartAudits: `http://localhost:8084`.

Los modulos usan perfil `dev`, que permite lectura sin token. **La aprobacion SmartAudits sigue exigiendo `SCOPE_observability:write`**, que solo llega en un token real.

## 9. Levantar los artifacts integrados

Despues de ejecutar las secciones 5 y 7:

```powershell
.\scripts\start-environment.ps1 -Mode Artifacts -SkipDatabaseInitialization
```

Este modo inicia unicamente tres contenedores Java 17 y **no** activa el perfil `dev`: los tres modulos validan firma, emisor y audiencia del token que emite Duma Web.

Comprobacion obligatoria antes de dar por buena una verificacion de seguridad:

```powershell
curl.exe -o NUL -w "%{http_code}`n" http://localhost:8082/api/lecturas
```

Debe devolver **401**. Un `200` significa que ese contenedor esta en standalone y cualquier prueba hecha contra el es un falso verde.

Para una primera corrida se puede omitir `-SkipDatabaseInitialization`; el script inicializa la base antes de arrancar.

## 10. Estado, logs y apagado

Estado de los procesos administrados:

```powershell
.\scripts\status-environment.ps1
```

Estado con las ultimas lineas de logs:

```powershell
.\scripts\status-environment.ps1 -IncludeLogs
```

Los logs locales estan en `.runtime\logs`. El estado con PIDs, nombres de contenedor y URLs esta en `.runtime\environment.json`. Ninguno debe contener secretos.

Apagado ordenado:

```powershell
.\scripts\stop-environment.ps1
```

El script solo detiene los PIDs y contenedores registrados por este proyecto.

## 11. Ejecutar un artifact por separado

Cada JAR es independiente. Para una ejecucion individual se deben inyectar las mismas variables documentadas en `config\runtime.example.ps1` y en el `backend\application.example.yml` del modulo. El password debe entrar desde el gestor de secretos o desde una variable de proceso preparada de forma segura, y Docker debe recibir unicamente el nombre de la variable con `-e`, nunca su valor en el argumento.

Ejemplo conceptual para Lecturas:

```powershell
. .\config\ports.local.ps1
. .\config\runtime.local.ps1
$credential = Get-Credential -UserName $env:DUMA_WAREHOUSE_MSSQL_USERNAME
$env:DUMA_WAREHOUSE_MSSQL_PASSWORD = $credential.GetNetworkCredential().Password
$env:DUMA_SYSTEMLOG_MSSQL_PASSWORD = $credential.GetNetworkCredential().Password
$env:DUMA_WAREHOUSE_MSSQL_HOST = 'host.docker.internal'
$env:DUMA_SYSTEMLOG_MSSQL_HOST = 'host.docker.internal'

docker run --rm --name duma-lecturas-standalone `
  --add-host host.docker.internal:host-gateway `
  -p ${env:DUMA_READINGS_BACKEND_PORT}:${env:DUMA_READINGS_BACKEND_PORT} `
  -e DUMA_READINGS_BACKEND_PORT `
  -e DUMA_SYSTEMLOG_MSSQL_HOST `
  -e DUMA_SYSTEMLOG_MSSQL_PORT `
  -e DUMA_SYSTEMLOG_MSSQL_DATABASE `
  -e DUMA_SYSTEMLOG_MSSQL_USERNAME `
  -e DUMA_SYSTEMLOG_MSSQL_PASSWORD `
  -e DUMA_WAREHOUSE_MSSQL_HOST `
  -e DUMA_WAREHOUSE_MSSQL_PORT `
  -e DUMA_WAREHOUSE_MSSQL_USERNAME `
  -e DUMA_WAREHOUSE_MSSQL_PASSWORD `
  -e DUMA_TENANT_CARLSJR_DATABASE `
  -v "${PWD}\artifacts\lecturas\lecturas.jar:/app/application.jar:ro" `
  eclipse-temurin:17-jre java -jar /app/application.jar --spring.profiles.active=dev

Remove-Item Env:DUMA_WAREHOUSE_MSSQL_PASSWORD,Env:DUMA_SYSTEMLOG_MSSQL_PASSWORD
```

El perfil `dev` es unicamente para lectura standalone local. **En integracion y despliegue se omite**, y entonces hacen falta ademas `DUMA_AUTH_ISSUER` y `DUMA_AUTH_JWKS_URI`, sin las cuales el contenedor no arranca.

## 12. Desplegar artifacts en otro host

Este repositorio no publica ni transfiere artifacts. El despliegue privado sigue este contrato:

1. ejecutar `scripts\build-artifacts.ps1` en CI o en una estacion autorizada;
2. comprobar los SHA-256 de `artifacts\manifest.json`;
3. transferir privadamente los tres JARs al host autorizado;
4. definir puertos, hosts, nombres de bases, URLs, badges y clearance como configuracion no secreta del orquestador;
5. **declarar `DUMA_AUTH_ISSUER` y `DUMA_AUTH_JWKS_URI`**, apuntando al emisor de la plataforma;
6. inyectar passwords desde el gestor de secretos del entorno;
7. usar Java 17 para cada JAR;
8. conservar los tres modulos en red interna: **ninguno necesita exposicion directa**, porque su unico consumidor es el BFF de Duma Web;
9. comprobar `/actuator/health` antes de enviar trafico;
10. comprobar que `GET /api/{modulo}` responde `401` sin token;
11. detener o revertir el release si un health check no queda `UP` o si la comprobacion anterior devuelve `200`.

Las llaves de firma **ya no se configuran aqui**. `DUMA_JWT_PRIVATE_KEY_BASE64`, `DUMA_JWT_PUBLIC_KEY_BASE64`, `DUMA_ALLOW_EPHEMERAL_KEYS`, `DUMA_SESSION_TIMEOUT` y `DUMA_SESSION_SECURE_COOKIE` pertenecian al portal: quien firma es ahora Duma Web, con su propia rotacion de llaves.

No se debe usar `--spring.profiles.active=dev` en stage o produccion. Los badges de cada modulo deben reflejar por separado `RELEASE_STAGE`, `DATA_ENVIRONMENT`, `FRESHNESS_MODE`, `TENANT_SCOPE` y `CLEARANCE`; el badge describe procedencia y alcance, pero no sustituye controles de red ni autorizacion.

## 13. Diagnostico directo

### Puerto ocupado

El arranque muestra el puerto y PID. Cambiar el valor en `config\ports.local.ps1`, no agregar `--port` manualmente.

### Falta un JAR

Ejecutar `scripts\build-artifacts.ps1`. El arranque no compila de forma implicita.

### Un modulo no arranca y el log menciona el JWKS

Falta `DUMA_AUTH_ISSUER` o `DUMA_AUTH_JWKS_URI`. Ninguna tiene valor por defecto, y es deliberado. Comprobar ademas que la URI **lleve el prefijo de grupo** de la ruta del emisor: sin el, la respuesta es 401 y el sintoma es indistinguible de una firma invalida.

### Todos los tokens son rechazados

Comprobar que el emisor configurado sea el que realmente firma. `NimbusJwtDecoder` y `JwtValidators` aceptan **un solo valor cada uno**: la confianza no es aditiva, y apuntar a un emisor invalida en el acto los tokens de cualquier otro.

### Un tenant aparece `UNAVAILABLE`

Revisar unicamente su variable `DUMA_TENANT_*_DATABASE`. Vacio significa no liberado; un nombre incorrecto o base inaccesible tambien se aisla a ese tenant.

### `NOT_SUPPORTED`

La base fue accesible, pero el esquema esperado por ese modulo no existe para ese tenant. No equivale a cero ni a falta de filas.

### `NO_DATA`

El esquema existe y la consulta fue valida, pero el periodo no devolvio registros.

### Una lectura pasa sin token cuando no deberia

El contenedor esta en standalone. Comprobar `DUMA_<MODULO>_STANDALONE_MODE` y que el arranque no lleve `--spring.profiles.active=dev`. Recordar que `-Mode Development` lo activa por diseno.

### Una vista de Duma Web no carga un modulo

Comprobar primero `status-environment.ps1`; despues `GET /api/{modulo}/manifest`, que es anonimo y responde aunque el token falle. Si el manifiesto responde y la lectura no, el problema es de token, no de arranque.
