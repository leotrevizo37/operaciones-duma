# Verificación backend final

- Fecha local: 2026-08-13, aproximadamente 06:34 a 06:36 `America/Mexico_City`.
- Estado: `PASS`.
- Checkout: el mismo utilizado por `runs/20260813-063353-validacion.md`, sin cambios intermedios de código.
- Secretos: no se cargaron ni imprimieron archivos `.env`, credenciales o valores privados.

## Comando

```text
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v <repo>:/workspace -v duma-maven-cache:/root/.m2 -w /workspace maven:3.9.14-eclipse-temurin-17 mvn verify
```

`<repo>` representa la ruta local privada y evita duplicarla en instrucciones portables.

## Resultado

| Aplicación | Unitarias | Integración SQL Server | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|---:|
| `app-shell` | 2 | 2 | 0 | 0 | 0 |
| `experiencia-digital` | 7 | 1 | 0 | 0 | 0 |
| `lecturas` | 2 | 1 | 0 | 0 | 0 |
| `smartaudits` | 3 | 3 | 0 | 0 | 0 |
| **Total** | **21** | **8** | **0** | **0** | **0** |

El reactor terminó con código `0` y `BUILD SUCCESS`. Spring Boot empaquetó los cinco JAR. Testcontainers utilizó SQL Server 2022; los avisos transitorios de prelogin ocurrieron mientras cada contenedor iniciaba y no produjeron fallos ni omisiones.

## Revalidación anti-omisión

Entre aproximadamente 06:48 y 06:49 se repitió el mismo `mvn verify` después de eliminar `disabledWithoutDocker` de los cinco tests de integración. Resultado: código `0`, 21 unitarias, ocho integraciones, cero fallos, errores u omisiones y cinco JAR empaquetados.

El validador de reportes Failsafe se ejecutó sobre los XML producidos y confirmó:

- `app-shell`: 2 ejecutadas, 0 omitidas.
- `experiencia-digital`: 1 ejecutada, 0 omitidas.
- `lecturas`: 1 ejecutada, 0 omitidas.
- `smartaudits`: 3 ejecutadas, 0 omitidas.

Con esta revisión, la falta de Docker impide aprobar `mvn verify` en lugar de producir una aceptación silenciosa con integraciones omitidas.
