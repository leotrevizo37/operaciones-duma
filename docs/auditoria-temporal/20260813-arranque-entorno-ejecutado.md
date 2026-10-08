# Bitacora del entorno levantado

Fecha local: 2026-08-13.

## Resultado

El entorno integrado quedó iniciado en modo `Development` a las `08:28:33 -06:00`. Los cinco backends y los cinco frontends respondieron HTTP 200. No se incluyó ninguna contraseña en comandos, archivos ni evidencia; la credencial SQL se introdujo mediante el prompt seguro de Windows.

## Comandos ejecutados

### 1. Inspección previa de puertos, contenedores y estado

```powershell
$ports=8080,8081,8082,8083,8084,5173,5174,5175,5176,5177
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object LocalPort -in $ports |
  Select-Object LocalAddress,LocalPort,OwningProcess
docker ps --format '{{.Names}} {{.Ports}}'
Test-Path -LiteralPath .runtime\environment.json
```

Resultado anterior al arranque: no existía `.runtime\environment.json` y no había listeners Duma registrados.

### 2. Primer intento de abrir la terminal de arranque

```powershell
Start-Process -FilePath powershell.exe `
  -ArgumentList @('-NoExit','-NoProfile','-ExecutionPolicy','Bypass','-File','C:\Users\Leonardo Trevizo\IdeaProjects\operaciones-duma\scripts\start-environment.ps1','-Mode','Development') `
  -WorkingDirectory 'C:\Users\Leonardo Trevizo\IdeaProjects\operaciones-duma'
```

Resultado: no inició el script porque `Start-Process` recompuso incorrectamente la ruta con espacios. No se inició ningún servicio.

### 3. Apertura corregida de la terminal y prompt seguro

```powershell
$scriptPath='C:\Users\Leonardo Trevizo\IdeaProjects\operaciones-duma\scripts\start-environment.ps1'
$arguments="-NoExit -NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`" -Mode Development"
Start-Process -FilePath powershell.exe `
  -ArgumentList $arguments `
  -WorkingDirectory 'C:\Users\Leonardo Trevizo\IdeaProjects\operaciones-duma'
```

Resultado: el script solicitó la credencial SQL mediante `Get-Credential`. Su valor no quedó en esta bitácora, en el historial ni en los argumentos del proceso.

El script de arranque ejecutó internamente:

1. carga de `config\ports.local.ps1` y `config\runtime.local.ps1`;
2. validación de los diez puertos;
3. inicialización idempotente de `DumaSystemLogs` mediante `scripts\initialize-databases.ps1`;
4. inicio de cinco contenedores `eclipse-temurin:17-jre`;
5. propagación de las variables `DUMA_*` por nombre a cada contenedor;
6. conversión de hosts SQL locales a `host.docker.internal` dentro de Docker;
7. inicio de cinco procesos Vite con `npm.cmd run dev`;
8. espera de cada health check y URL frontend;
9. escritura del estado operativo en `.runtime\environment.json`.

### 4. Monitoreo del proceso de arranque

```powershell
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
  Where-Object CommandLine -Like '*start-environment.ps1*' |
  Select-Object ProcessId,CreationDate,CommandLine
Test-Path -LiteralPath .runtime\environment.json
```

Resultado: proceso de arranque activo y archivo de estado creado.

### 5. Comprobación final de los diez endpoints y cinco contenedores

```powershell
$urls=@(
  'http://localhost:8080/actuator/health',
  'http://localhost:8081/actuator/health',
  'http://localhost:8082/actuator/health',
  'http://localhost:8083/actuator/health',
  'http://localhost:8084/actuator/health',
  'http://localhost:5173',
  'http://localhost:5174',
  'http://localhost:5175',
  'http://localhost:5176',
  'http://localhost:5177'
)
foreach($url in $urls) {
  Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 4
}
docker ps --filter 'name=duma-' --format '{{.Names}}|{{.Status}}|{{.Ports}}'
```

Resultado: los diez endpoints devolvieron HTTP 200 y los cinco contenedores quedaron `Up`.

## Inventario en ejecución

| Componente | Tipo | URL o health | Estado verificado |
|---|---|---|---|
| App shell | Frontend Vite | `http://localhost:5173` | HTTP 200 |
| Experiencia digital | Frontend Vite | `http://localhost:5174` | HTTP 200 |
| Lecturas | Frontend Vite | `http://localhost:5175` | HTTP 200 |
| SmartAudits | Frontend Vite | `http://localhost:5177` | HTTP 200 |
| App shell | Backend Java 17 | `http://localhost:8080/actuator/health` | HTTP 200 |
| Experiencia digital | Backend Java 17 | `http://localhost:8081/actuator/health` | HTTP 200 |
| Lecturas | Backend Java 17 | `http://localhost:8082/actuator/health` | HTTP 200 |
| SmartAudits | Backend Java 17 | `http://localhost:8084/actuator/health` | HTTP 200 |

Contenedores en ejecución:

- `duma-app-shell`
- `duma-experiencia-digital`
- `duma-lecturas`
- `duma-smartaudits`

## Operación posterior

```powershell
.\scripts\status-environment.ps1
.\scripts\status-environment.ps1 -IncludeLogs
.\scripts\stop-environment.ps1
```

La aplicación debe permanecer en ejecución hasta que se invoque explícitamente el script de apagado o Docker Desktop/procesos del host sean detenidos.

## Provisionamiento del acceso web

Después del arranque se ejecutó:

```powershell
$scriptPath='C:\Users\Leonardo Trevizo\IdeaProjects\operaciones-duma\scripts\provision-shell-user.ps1'
$arguments="-NoExit -NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`" -Username investigador -DisplayName `"Leonardo Trevizo`""
Start-Process -FilePath powershell.exe `
  -ArgumentList $arguments `
  -WorkingDirectory 'C:\Users\Leonardo Trevizo\IdeaProjects\operaciones-duma'
```

El password de la aplicación y la credencial SQL se capturaron mediante prompts seguros. No se registraron sus valores. El password de aplicación se transformó a BCrypt antes de invocar `security.usp_UpsertAppUser`.
