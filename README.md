# Operaciones Duma

Monorepo privado de servicios de salud del dato para Duma. Cada aplicacion conserva su propio backend, inicializacion de auditoria y pruebas para poder ejecutarse y migrarse de forma independiente.

## Aplicaciones

| Directorio | Dominio | Backend |
|---|---|---:|
| `experiencia-digital` | Experiencia de usuarios y disponibilidad | 8081 |
| `lecturas` | Continuidad y excepciones de lecturas | 8082 |
| `smartaudits` | SmartAudits y cola de revision humana | 8084 |

## Principios del corte

- **Duma Web es el unico punto de autenticacion real** y quien rinde el resumen ejecutivo. Los modulos no implementan login.
- Los modulos exponen contratos HTTP versionados: `GET /api/{modulo}`, su manifiesto y su frescura. No exponen UI.
- **Duma Web emite los tokens**: RS256, vida corta y `aud` exclusiva por modulo. Un token de un modulo no lo acepta otro aunque la firma sea valida.
- `DUMA_AUTH_ISSUER` y `DUMA_AUTH_JWKS_URI` **no tienen valor por defecto**. Un modulo que no las declare no arranca, y es deliberado: con respaldo, un despliegue que las olvidara seguiria confiando en quien estuviera escrito en el arbol.
- Los permisos viajan en el token, pero solo `smartaudits` aplica una regla: aprobar en su cola exige `SCOPE_observability:write`.
- Cada tenant informa `AVAILABLE`, `NO_DATA`, `NOT_SUPPORTED` o `UNAVAILABLE`; la ausencia de una tabla o de filas nunca se representa como cero.
- Cada modulo declara por separado su etapa de liberacion, entorno y frescura del dato, alcance y clasificacion academica.
- Los system logs se escriben en SQL Server mediante host, puerto y base configurables.
- Los tres puertos se fijan mediante `config/ports.example.ps1`.
- **Hay un solo camino de arranque**: `scripts\start-environment.ps1`. El de `.env` por aplicacion con `compose.yaml` y `make up` se retiro porque forzaba `DUMA_*_STANDALONE_MODE: true` y hacia pasar por verde cualquier comprobacion de autenticacion.
