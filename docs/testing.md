# Estrategia de pruebas

Cada aplicacion contiene tres capas independientes:

1. Unitarias: reglas de cobertura, validacion, calculos de veredicto, componentes y estados vacios.
2. Integracion: API, SQL parametrizado y transacciones contra SQL Server real mediante Testcontainers.
3. Contrato: que el manifiesto declare `2.0` sin los campos del protocolo retirado, y que las rutas exentas de autenticacion sigan siendo exactamente `/api/module/manifest` y `/actuator/health`.

La capa E2E desaparecio con los frontends: este repositorio ya no sirve UI, y las vistas que la ejercen viven en Duma Web con su propia suite.

Las pruebas de cobertura multi-tenant deben demostrar que:

- un tenant disponible no queda oculto por otro sin tabla;
- `NO_DATA` no se convierte en cero;
- `NOT_SUPPORTED` no se convierte en error global;
- `UNAVAILABLE` conserva una causa sanitizada;
- el orden de tenants es determinista;
- la analitica de SmartAudits conserva cobertura independiente para los siete tenants;
- la cola de revision humana de SmartAudits no consulta ni modifica tenants distintos de `carlsjr`.

SmartAudits agrega pruebas transaccionales de llave compuesta, categoria invalida, idempotencia, rollback y consistencia cola-lookup.

## Reproduccion

La matriz completa se ejecuta desde la raiz:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate.ps1 -PortsFile .\config\ports.example.ps1
```

Cada backend conserva unitarias Surefire e integraciones Failsafe dentro del reactor Maven raiz. **`validate.ps1` ya no invoca `npm`**: sin frontends no queda nada que instalar, lintar ni construir con Node, y por eso perdio tambien `-Install` y `-SkipE2E`. Las integraciones levantan SQL Server mediante Testcontainers y requieren Docker Desktop.

La ausencia de Docker hace fallar Failsafe; no convierte las integraciones en omitidas. Al terminar Maven, el runner inspecciona los XML de los tres backends y exige al menos una integracion ejecutada y cero `skipped` en cada uno.

Los tres `PortConfigurationTest` cargan el `application.yml` real y comprueban que puertos alternativos se propaguen a servidor, API, issuer, JWKS y CORS. **Ya no comprueban URLs remotas**: `remote-entry-url` salio del manifiesto con el protocolo.

Los tres `ModuleSecurityConfigTest` custodian lo que el retiro del portal declaro preservado —manifiesto y sonda anonimos, lectura autenticada— y, en `smartaudits`, las dos ramas de su cola de revision. Existen porque hasta 2026-08-26 solo `smartaudits` tenia pruebas de autorizacion, y sin ellas tocar esas reglas habria sido un cambio que ningun test observa.

Los resultados de cada ejecucion se conservan en `docs/auditoria-temporal`; una prueba solo se considera aprobada cuando su evidencia indica `PASS` y no registra omisiones.
