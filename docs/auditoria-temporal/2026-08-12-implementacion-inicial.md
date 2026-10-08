# Bitácora de implementación inicial

## Alcance auditado

- Contrato de integración Web Component `1.0`, autenticación central y JWT por audiencia.
- Cobertura aislada para siete tenants y distinción entre `AVAILABLE`, `NO_DATA`, `NOT_SUPPORTED` y `UNAVAILABLE`.
- System logs SQL Server por aplicación.
- Flujo transaccional de promoción SmartAudits limitado a `carlsjr`.
- Diez puertos deterministas propagados a backend, frontend, proxy, CORS, issuer, JWKS, URLs remotas y E2E.

No se leyeron archivos `.env`, secretos ni credenciales. No se realizó commit, push, publicación o despliegue.

## Ejecuciones y resultados

| Ejecución | Estado | Resultado | Acción derivada |
|---|---|---|---|
| Inspección de patrones en `icos-internal-chat` y `salubridad` | PASS | Se identificaron separación backend/frontend, contratos de datos y los siete tenants sin extraer secretos | Se mantuvieron contratos locales y módulos independientes |
| Instalación de dependencias npm y generación de cinco `package-lock.json` | PASS | Resolución reproducible completada para los cinco frontends | Los locks quedaron versionables por aplicación |
| Primeras corridas de lint frontend | FAIL | Archivos TypeScript generados y una regla de limpieza DOM produjeron errores | Se excluyeron artefactos generados y se corrigió el aislamiento de pruebas |
| Lint, unitarias y build de los cinco frontends tras corrección | PASS | Cinco lint, diez pruebas unitarias y cinco builds completados | Base frontend aceptada |
| Primer E2E de los cinco frontends | PASS | Siete escenarios funcionales completados | Se aceptaron estados standalone, badges, seguridad y promoción SmartAudits |
| Prueba E2E con servidores externos y puertos alternativos | INVALID | Los escenarios pasaron, pero el proceso padre excedió el timeout al cerrar servidores en Windows | Playwright pasó a iniciar y cerrar Vite mediante `globalSetup` |
| E2E con `globalSetup` y puertos 55173 a 55177 | PASS | Siete escenarios completados; Vite respetó cada puerto alternativo sin fallback | Se aceptó el ciclo de vida determinista del servidor E2E |
| `mvn verify` sin acceso al socket Docker | INVALID | El reactor compiló, pero Testcontainers omitió las pruebas de integración | La corrida no se utilizó como evidencia de integración |
| `mvn verify` con socket Docker y SQL Server Testcontainers | PASS | Ocho pruebas de integración, cero fallos, errores u omisiones | Se aceptaron identidad SQL, consultas multi-tenant y promoción transaccional |
| `mvn test` después de propagar puertos | PASS | 21 pruebas unitarias, incluidas cinco cargas reales de `application.yml`; cero fallos, errores u omisiones | Se aceptó la resolución de puertos backend |
| Build al incorporar E2E al proyecto TypeScript de SmartAudits | FAIL | TypeScript rechazó el import absoluto de `remote-entry.js` sin declaración estática | El E2E pasó a cargar el módulo mediante un elemento `script` y un `Promise` tipado |
| Invocación directa de `scripts/validate.ps1` | INVALID | La política local de PowerShell impidió cargar el archivo antes de iniciar pruebas | La reproducción usa `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` sin cambiar la política del equipo |
| Primera invocación del script con bypass de proceso | FAIL | Un aviso de Docker escrito en `stderr` fue elevado por Windows PowerShell aunque el binario estaba disponible | El runner conserva `stderr`, pero determina éxito exclusivamente mediante el código de salida nativo |
| `npm ci` del shell dentro de un contenedor Node | INVALID | npm instaló dependencias, pero los enlaces ejecutables Linux no restauraron los shims `.cmd` requeridos por Windows | Se reinstaló el mismo lockfile mediante `npm.cmd ci --ignore-scripts` en el host |
| Reinstalación limpia del shell en el host | PASS | 258 paquetes restaurados desde `package-lock.json`; npm informó una vulnerabilidad alta pendiente | El frontend volvió a ejecutar `tsc`, Vite, Vitest, ESLint y Playwright; no se alteraron dependencias fuera del alcance aprobado |
| Validación reproducible del 13 de agosto | INVALID | Cinco lint, diez unitarias, cinco builds y ocho E2E pasaron; el PowerShell restringido no heredó acceso al socket Docker | Se conservó la salida en `runs/20260813-063353-validacion.md` y el backend se ejecutó directamente con el prefijo Docker autorizado |
| Reactor Maven final directo | PASS | 21 unitarias y ocho integraciones SQL Server; cero fallos, errores u omisiones; cinco JAR empaquetados | Evidencia en `20260813-0634-backend-verify.md` |
| E2E final con puertos alternativos | PASS | Ocho escenarios en 55173, 55174, 55175, 55176 y 55177; cero fallos | Evidencia en `20260813-puertos-alternativos.md` |
| Revisión inicial de whitespace | FAIL | 42 archivos nuevos tenían una línea vacía adicional al final | Se normalizó únicamente el EOF y la repetición pasó sobre 229 archivos |
| Parseo JSON con `ConvertFrom-Json` de Windows PowerShell | INVALID | El parser local rechazó una forma válida de `package-lock.json` | Node validó los 27 JSON mediante `JSON.parse` |
| Verificación de artefactos y variables | PASS | No quedaron estáticos, `tsbuildinfo` ni configuraciones JS generadas; 53 variables del shell y 38 por módulo están documentadas | Checkout aceptado para cierre |
| Normalización de logs del runner | PASS | Tres bitácoras mixtas se convirtieron mecánicamente a UTF-8 y el runner ahora escribe UTF-8 explícito | Evidencia Markdown legible y portable |
| Endurecimiento anti-omisión de integraciones | PASS | Se eliminó `disabledWithoutDocker`; el runner exige evidencia Failsafe por backend | Reactor repetido: 21 unitarias y ocho integraciones, cero `skipped`; XML validado 2/1/1/1/3 |

## Criterios conservados en código

- Cada `package.json` mantiene scripts separados para lint, unitarias, build y E2E.
- Cada backend participa en el reactor Maven raíz y separa Surefire de Failsafe.
- Los tests `PortConfigurationTest` validan que los puertos canónicos lleguen a las propiedades derivadas.
- Playwright usa `strictPort` y el mismo nombre de variable que Vite.
- `scripts/validate.ps1` valida rango y unicidad, registra valores no secretos, comandos, salida y resultado final.

La aceptación final se compone de la matriz frontend registrada en `runs/20260813-063353-validacion.md`, los puertos alternativos en `20260813-puertos-alternativos.md` y el reactor backend registrado en `20260813-0634-backend-verify.md`. La separación responde exclusivamente a la frontera del sandbox. Después del único endurecimiento de pruebas, el reactor backend se repitió y quedó nuevamente aprobado.
