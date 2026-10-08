# Auditoría temporal de implementación

Esta carpeta conserva evidencia local y reproducible de las ejecuciones realizadas durante el desarrollo. Su contenido forma parte del proyecto académico privado y no debe publicarse ni copiarse a servicios externos.

## Estados

- `PASS`: la ejecución terminó correctamente y su resultado es evidencia válida.
- `FAIL`: la ejecución detectó un defecto verificable.
- `INVALID`: hubo resultados parciales, pero la ejecución no puede utilizarse como evidencia de aprobación.
- `SKIPPED`: una capa declarada no se ejecutó.

Los fallos e intentos inválidos se conservan junto con la corrección aplicada. No se registran secretos, valores de `.env`, credenciales, tokens ni filas de negocio.

## Evidencia

- `2026-08-12-implementacion-inicial.md`: bitácora consolidada de construcción y validaciones iterativas.
- `20260813-0634-backend-verify.md`: evidencia final del reactor Maven y SQL Server Testcontainers.
- `20260813-puertos-alternativos.md`: E2E final con los cinco frontends en puertos no predeterminados.
- `runs/*-validacion.md`: salida completa generada por `scripts/validate.ps1` para cada corrida reproducible.

## Reproducción

Desde la raíz del repositorio:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate.ps1 -Install -PortsFile .\config\ports.example.ps1
```

La validación carga y comprueba los diez puertos, ejecuta lint, unitarias, build y E2E de cada frontend, y después ejecuta el reactor Maven con pruebas unitarias y de integración SQL Server mediante Testcontainers.
