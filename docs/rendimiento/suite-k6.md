# Suite modular de rendimiento (k6)

La suite de `performance-tests/k6/scenarios/` tiene el humo y las doce pruebas
de rendimiento de #331: carga, estrés, pico, resistencia, volumen,
concurrencia, escalabilidad, tiempo de respuesta, API, base de datos,
recuperación y capacidad. Corre en el servicio `k6` de Docker Compose, dentro
de la red, así que no hay que instalar k6 ni abrir puertos.

> Esta es la suite modular nueva. Los escenarios por servicio que ya existían
> (`gateway/`, `ms-auth/`, `ms-debt/`, `ms-payments/`, `ms-ai/` y `portal/`)
> siguen documentados en el [README](README.md), con sus corridas históricas.

## Antes de correrla

- Docker Desktop con la pila local. La suite **no se corre contra producción**:
  si `PORTAL_URI`, `GATEWAY_URI` o `API_BASE` no apuntan a un host local, se
  detiene antes de escribir nada.
- Un `.env` creado con `preparar-env.ps1`. Si falta, `scripts/run.ps1` lo crea.
- Para la primera corrida, `-Levantar` levanta la pila con datos de demo y los
  cupos del gateway y del acceso altos, y espera a que esté sana:

```powershell
.\performance-tests\k6\scripts\run.ps1 smoke -Levantar
```

Si la pila ya está arriba, se corre sin `-Levantar`:

```powershell
.\performance-tests\k6\scripts\run.ps1 smoke
```

El equivalente con Docker Compose, sin el envoltorio de PowerShell:

```powershell
docker compose --profile carga run --rm k6 run /performance-tests/k6/scenarios/smoke.js
```

`run.ps1` acepta `-Levantar` (levanta la pila y sube los cupos), `-SinIa`
(fuerza `INCLUIR_IA=false`) y `-SoloLectura` (fuerza
`PERMITIR_ESCRITURAS=false`).

> **Cupos del gateway.** Todo el tráfico de k6 sale de una sola IP y el
> limitador del gateway cuenta por IP. `-Levantar` sube
> `RATE_GLOBAL_CAPACITY`, `RATE_AUTH_CAPACITY` y
> `AUTH_IDENTIFIER_LIMIT_CAPACITY` **solo para esa corrida**. Cualquier
> `docker compose up` posterior sin esas variables devuelve los cupos de
> fábrica y la prueba mediría el limitador, no el sistema. Para restaurarlos:

```powershell
Remove-Item Env:\RATE_GLOBAL_CAPACITY, Env:\RATE_AUTH_CAPACITY, Env:\AUTH_IDENTIFIER_LIMIT_CAPACITY
docker compose --profile app up -d --wait gateway ms-auth
```

Si un escenario informa `limitador_intervino`, el resultado no sirve: se está
midiendo el limitador. Vuelve a levantar con `-Levantar` y repite.

## Comandos por escenario

Todos los comandos se pueden correr desde la raíz del repositorio. Cada uno
ejecuta `docker compose --profile carga run --rm k6 run
/performance-tests/k6/scenarios/<archivo>.js` dentro de la red.

| # | Escenario | Comando | Qué mide |
| --- | --- | --- | --- |
| 0 | Humo | `.\performance-tests\k6\scripts\run.ps1 smoke` | Sesión de deudor y de empresa, lecturas, pagos, asistente, resumen y revisión de cartera CSV/XLSX. No busca capacidad: comprueba que la pila y la suite se hablan. |
| 1 | Carga | `.\performance-tests\k6\scripts\run.ps1 load` | Usuarios concurrentes con pausas, por el camino completo (nginx → gateway → servicio → MySQL). |
| 2 | Estrés | `.\performance-tests\k6\scripts\run.ps1 stress` | Ritmo creciente sobre `GET /api/debts` hasta que el p95 pasa de 2 s; el umbral aborta para no recalentar la máquina. |
| 3 | Pico | `.\performance-tests\k6\scripts\run.ps1 spike` | Subida súbita de 5 a 150 usuarios, meseta y vuelta a 5; mide el pico y la recuperación. |
| 4 | Resistencia | `.\performance-tests\k6\scripts\run.ps1 endurance` | Ritmo constante durante 10 minutos (configurable con `DURACION_ENDURANCE`) para detectar fugas o degradación. |
| 5 | Volumen | `.\performance-tests\k6\scripts\run.ps1 volume` | Revisión de carteras grandes CSV y XLSX (400 deudores y 800 filas de deuda por archivo). La revisión siempre hace rollback. |
| 6 | Concurrencia | `.\performance-tests\k6\scripts\run.ps1 concurrency` | Consultas simultáneas y, si `PERMITIR_ESCRITURAS=true`, tres repactaciones en deudas distintas. |
| 7 | Escalabilidad | `.\performance-tests\k6\scripts\run.ps1 scalability` | Escalones de 10, 25 y 50 usuarios, con latencia etiquetada por nivel. |
| 8 | Tiempo de respuesta | `.\performance-tests\k6\scripts\run.ps1 response-time` | Un solo usuario recorriendo cada operación diez veces, sin ruido de concurrencia. |
| 9 | API | `.\performance-tests\k6\scripts\run.ps1 api-performance` | Ritmo constante contra el gateway, con latencia, errores y un mínimo de throughput. |
| 10 | Base de datos | `.\performance-tests\k6\scripts\run.ps1 database-performance` | Lecturas pesadas de ms-debt que hacen joins y agregaciones contra MySQL; no hay endpoint SQL directo. |
| 11 | Recuperación | `.\performance-tests\k6\scripts\run.ps1 recovery` | 30 s de sobrecarga, una pausa y 30 s de carga normal: lo que importa es que vuelva. |
| 12 | Capacidad | `.\performance-tests\k6\scripts\run.ps1 capacity` | Escalones de 50, 100, 200 y 400 peticiones/s para ubicar la capacidad sostenible. |

## Variables

Las variables se leen de `config/env.js`. Las que están en el `environment`
del servicio `k6` se pueden definir en la terminal antes de `run.ps1`; las
demás se pasan con `-e` al comando de Docker Compose.

| Variable | Por omisión | Para qué sirve |
| --- | --- | --- |
| `PERMITIR_ESCRITURAS` | `false` | Con `true`, la prueba de concurrencia repacta tres deudas de la base local. Anula cuotas pendientes y **no se deshace solo**. Úsala solo con datos de demo. |
| `INCLUIR_IA` | `false` | Con `true`, los recorridos y las pruebas de API mandan preguntas al asistente. Con `LLM_PROVIDER=groq` se gasta el proveedor real (ver abajo). |
| `VERIFICAR_PASARELAS` | `false` | Con `true`, el humo abre un cobro en Webpay, Khipu y Mercado Pago y falla si alguno no es simulado. No confirma pagos; deja tres cobros abiertos en la base local. |
| `MAX_VUS` | `20` | Usuarios de la meseta alta en `load` y en la parte de consultas de `concurrency`. |
| `MAX_VUS_ESTRES` | `500` | Tope de VUs del executor de `stress`. |
| `TASA_CARGA` | `20` | Iteraciones por segundo de `endurance`. |
| `TASA_API` | `20` | Peticiones por segundo de `api-performance`. |
| `TASA_RECUPERACION` | `10` | Usuarios de la fase de recuperación. |
| `DURACION_CARGA` | `2m` | Duración de `api-performance` y `database-performance`. |
| `DURACION_ENDURANCE` | `10m` | Duración de `endurance`. |
| `ITERACIONES_RESPUESTA` | `10` | Repeticiones por operación en `response-time`. |
| `REPETICIONES_VOLUMEN` | `1` | Repeticiones de la revisión de carteras en `volume`. |
| `NIVELES_ESCALABILIDAD` | `10,25,50` | Niveles de usuarios de `scalability`, separados por coma. |
| `TASAS_CAPACIDAD` | `50,100,200,400` | Niveles de peticiones/s de `capacity`, separados por coma. |
| `RUT_DEUDOR` | `16482337-7` | Deudor de la demo con el que se arman las sesiones. |
| `CORREO_EMPRESA` | `camila.reyes@example.com` | Buzón de la empresa de la demo; el enlace se lee de Mailpit. |
| `RUTS_CONCURRENCIA` | `16482337-7,17893456-2,76284519-9` | Deudores distintos para las repactaciones de `concurrency`. |
| `REPORT_DIR` | `reports` | Carpeta de los reportes, dentro de `performance-tests/k6/`. |
| `PORTAL_URI`, `GATEWAY_URI`, `API_BASE` | hosts de Compose | Destinos de la suite. `API_BASE` cae en el gateway. |
| `PERMITIR_ENTORNO_NO_LOCAL` | `false` | **No la uses** salvo en un entorno descartable. Permite correr contra un host que no es local. |

Ejemplo con variables para una corrida puntual, sin tocar el `.env`:

```powershell
docker compose --profile carga run --rm `
  -e INCLUIR_IA=true -e MAX_VUS=50 `
  k6 run /performance-tests/k6/scenarios/load.js
```

### Advertencia: proveedor real de IA

Con `LLM_PROVIDER=groq` e `INCLUIR_IA=true`, cada pregunta del asistente sale
al proveedor real: **gasta cupo y le manda datos de la deuda del deudor**. Por
eso `INCLUIR_IA` viene en `false`, tanto en `config/env.js` como en
`docker-compose.yml`. Para medir latencia comparable, usa
`LLM_PROVIDER=local`; si de verdad necesitas el proveedor real, enciéndelo a
propósito y no interpretes los umbrales de `ia_chat` como una comparación del
sistema.

### Advertencia: pasarelas

La suite no confirma pagos ni sigue la URL de una pasarela. Aun así, no la
apuntes a los ambientes de prueba de Transbank, Khipu o Mercado Pago: son
compartidos. Para el humo con `VERIFICAR_PASARELAS=true`, deja las pasarelas
simuladas en el `.env` (`TRANSBANK_ENVIRONMENT=SIMULADA`, `KHIPU_LLAVE=""`,
`MERCADOPAGO_ACCESS_TOKEN=""`).

## Umbrales

Los umbrales son criterios de aceptación: si uno falla, k6 termina con código
distinto de cero. `suite_duracion` mide la latencia por operación y
`suite_tasa_exito` la tasa de respuestas válidas. Además, casi todos los
escenarios tienen `limitador_intervino` con `count<1`: si el gateway responde
aunque sea un `429`, la corrida se aborta porque estaría midiendo el
limitador.

| Escenario | Umbrales principales |
| --- | --- |
| Humo | `http_req_failed < 1 %`, `suite_tasa_exito > 99 %`; cartera CSV p95 < 15 s y XLSX p95 < 20 s. |
| Carga | `http_req_failed < 1 %`, `suite_tasa_exito > 99 %`; lista, detalle y simulación p95 < 800 ms; pagos, `/api/me` y salud IA < 500 ms; resumen < 1.500 ms; chat < 2.000 ms si `INCLUIR_IA=true`. |
| Estrés | `http_req_failed < 2 %`, `suite_tasa_exito > 98 %`; lista p95 < 2.000 ms con aborto. |
| Pico | `http_req_failed < 2 %`; pico p95 < 2.500 ms y tasa > 95 %; después p95 < 1.000 ms y tasa > 99 %. |
| Resistencia | `http_req_failed < 1 %`, `suite_tasa_exito > 99 %`; lista, detalle y simulación p95 < 1.000 ms. |
| Volumen | `http_req_failed < 1 %`, `suite_tasa_exito > 99 %`; CSV p95 < 20 s y XLSX p95 < 25 s. |
| Concurrencia | `http_req_failed < 5 %`, `suite_tasa_exito > 95 %`; lista, detalle y simulación p95 < 1.500 ms; repactar p95 < 3.000 ms si hay escrituras. |
| Escalabilidad | `http_req_failed < 1 %`; p95 < 800 ms hasta 25 usuarios, < 1.200 ms hasta 50 y < 2.000 ms por encima; tasa > 99 % por nivel. |
| Tiempo de respuesta | `http_req_failed < 1 %`, `suite_tasa_exito > 99 %`; portal < 500 ms, `/api/me` < 300 ms, lista/detalle/simulación < 800 ms, pagos < 500 ms, resumen < 1.500 ms, salud IA < 500 ms; chat < 1.500 ms si `INCLUIR_IA=true`. |
| API | `http_req_failed < 1 %`, `suite_tasa_exito > 99 %`, `http_reqs > 30/s`; `/api/me` < 500 ms, lista/simulación < 800 ms, pagos y salud IA < 500 ms, resumen < 1.500 ms; chat < 2.000 ms si `INCLUIR_IA=true`. |
| Base de datos | `http_req_failed < 1 %`, `suite_tasa_exito > 99 %`; lista, detalle, historial y vencimientos p95 < 800 ms; resumen < 1.500 ms. |
| Recuperación | `http_req_failed < 2 %`; sobrecarga p95 < 5.000 ms; recuperación p95 < 1.000 ms y tasa > 99 %. |
| Capacidad | `http_req_failed < 2 %`, `suite_tasa_exito > 98 %`; p95 < 800 ms hasta 100 peticiones/s, < 1.200 ms hasta 200 y < 5.000 ms en 400. |

En `stress`, `recovery` y `capacity` un código de salida distinto de cero
puede ser el resultado esperado: el freno de mano encontró el techo. Lo que no
se acepta es un error de verdad ni un `429` del limitador.

## Reportes

Cada corrida escribe en `performance-tests/k6/reports/`:

- `<escenario>-<fecha>.json`: métricas completas para comparar entre fechas.
- `<escenario>-<fecha>.md`: resumen corto para pegar en un issue o en este
  directorio.

`reports/` está ignorado por git (`reports/.gitignore`). El JSON ya **no
incluye `setup_data`**, así que no arrastra los tokens de las sesiones; aun
así, no compartas reportes con datos de una base que no sea de demo.

## Datos de prueba

Las carteras son ficticias:

- `data/cartera-pequena.csv` y `.xlsx`: humo.
- `data/cartera-volumen.csv` y `.xlsx`: 400 deudores y 800 filas de deuda por
  archivo, para `volume`.

Si se cambia el tamaño, se regeneran con:

```powershell
python performance-tests/k6/scripts/generar-datos.py
```

La revisión de cartera (`POST /api/debts/cartera/revision`) pasa por la misma
ingesta que una carga real, pero siempre hace rollback: no deja deudas, lotes,
contactos ni correos. Sin contar los cobros opcionales de
`VERIFICAR_PASARELAS`, la repactación de `concurrency` es la única escritura
que queda en la base, y solo con `PERMITIR_ESCRITURAS=true`.
