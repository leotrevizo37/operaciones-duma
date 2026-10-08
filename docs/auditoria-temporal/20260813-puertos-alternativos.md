# E2E con puertos alternativos

- Fecha local: 2026-08-13.
- Estado: `PASS`.
- Propósito: demostrar que Playwright y Vite usan el puerto configurado y no seleccionan otro silenciosamente.

| Aplicación | Variable | Puerto | Escenarios | Resultado |
|---|---|---:|---:|---|
| `app-shell` | `DUMA_SHELL_FRONTEND_PORT` | 55173 | 3 | PASS |
| `experiencia-digital` | `DUMA_EXPERIENCE_FRONTEND_PORT` | 55174 | 1 | PASS |
| `lecturas` | `DUMA_READINGS_FRONTEND_PORT` | 55175 | 1 | PASS |
| `smartaudits` | `DUMA_SMARTAUDITS_FRONTEND_PORT` | 55177 | 2 | PASS |

Cada ejecución utilizó la forma:

```powershell
$env:<VARIABLE_FRONTEND> = '<PUERTO_ALTERNATIVO>'
npm.cmd run e2e
```

Resultado agregado: ocho escenarios aprobados, cero fallos. La cobertura incluye overflow horizontal, estados multi-tenant, handshake válido e inválido, bloqueo sin JWT y promoción SmartAudits mediante el contrato Web Component.
