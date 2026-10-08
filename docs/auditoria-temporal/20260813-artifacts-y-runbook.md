# Evidencia temporal: artifacts y runbook local

Fecha local: 2026-08-13.

## Alcance

Se prepararon scripts reproducibles para configurar puertos y runtime no secreto, inicializar SQL Server, crear usuarios del shell, construir artifacts, iniciar, consultar estado y detener el entorno. No se leyeron archivos `.env`, no se guardaron credenciales y no se publicó ni desplegó contenido fuera del equipo.

## Ejecuciones realizadas

1. Análisis sintáctico de los ocho scripts PowerShell: resultado `OK` en todos.
2. `npm.cmd run build` en los cinco frontends: resultado exitoso en los cinco.
3. `mvn verify` del reactor completo dentro de `maven:3.9.14-eclipse-temurin-17`, con Testcontainers SQL Server: código de salida `0`.
4. Empaquetado local de los cinco JARs ya validados en `artifacts\`.
5. Generación de `artifacts\manifest.json` con tamaño y SHA-256 por JAR.
6. Comprobación focalizada del generador BCrypt empaquetado. La primera invocación expuso un problema de quoting de PowerShell; se cambió a un arreglo explícito de argumentos y la comprobación corregida fue exitosa.

## Artefactos generados

- `artifacts\app-shell\app-shell.jar`
- `artifacts\experiencia-digital\experiencia-digital.jar`
- `artifacts\lecturas\lecturas.jar`
- `artifacts\smartaudits\smartaudits.jar`
- `artifacts\manifest.json`

`artifacts\`, `.runtime\` y los dos archivos `config\*.local.ps1` están ignorados por Git.

## Límite de la ejecución

No se ejecutó el arranque conectado al SQL Server local porque hacerlo desde una llamada automatizada exigiría insertar una credencial real en argumentos o salida de herramienta. El recorrido queda preparado para pedirla interactivamente mediante `Get-Credential`; el comando exacto está en `docs\runbook-entorno-y-artifacts.md`.
