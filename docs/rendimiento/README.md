# Pruebas de rendimiento

Escenarios de carga y estrés implementados en **Gatling Java DSL** y suites de comprobación rápida de SLA en **JUnit 5 con AssertJ** (`PerformTest*`). Se ejecutan directamente con el Maven Wrapper (`mvnw`), midiendo tanto a través del gateway como directamente sobre cada microservicio.

| Servicio | Clase Gatling / Prueba | Qué mide |
| --- | --- | --- |
| gateway | `LoadSimulationGateway` / `PerformTestGateway` | Enrutar una petición a cada servicio, aislando el gateway |
| ms-auth | `LoadSimulationMsAuth` / `PerformTestMsAuth` | Lecturas de sesión: `GET /api/me` y llaves públicas `jwks.json` |
| ms-debt | `LoadSimulationMsDebt` / `PerformTestMsDebt` | Capacidad del deudor leyendo su deuda por escalones |
| ms-debt | `StressSimulationMsDebt` | Estrés: cuánto aguanta la lista de deudas hasta saturar |
| ms-payments | `LoadSimulationMsPayments` / `PerformTestMsPayments` | Concurrencia al abrir cobros con pasarelas simuladas |
| ms-payments | `StressSimulationMsPayments` | Estrés: cuánto aguanta leer el historial de pagos |
| ms-ai | `LoadSimulationMsAI` / `PerformTestMsAI` | Salud del asistente y concurrencia del chat (motor local) |
| ms-ai | `StressSimulationMsAI` | Estrés del endpoint de salud y chat bajo sobrecarga |

## Guía rápida: Ejecución en otro equipo (Paso a paso)

Para correr las pruebas de rendimiento en una máquina nueva desde cero (Windows, Linux o macOS):

### 1. Preparar el entorno

```bash
# 1. Clonar el repositorio
git clone <URL_DEL_REPOSITORIO>
cd DataBridge

# 2. Configurar variables de entorno y claves JWT
```

* **En Windows (PowerShell):**
  ```powershell
  # Genera el archivo .env con secretos aleatorios y RATE_GLOBAL_CAPACITY=10000000
  .\preparar-env.ps1
  # Genera el par de claves criptográficas ES256 para JWT en .secrets/jwt/
  .\scripts\generar-claves-jwt.ps1
  ```

* **En Linux / macOS (Bash):**
  ```bash
  cp .env.example .env
  # Asegurar RATE_GLOBAL_CAPACITY=10000000 en .env para que el gateway no bloquee con 429
  mkdir -p .secrets/jwt
  openssl ecparam -name prime256v1 -genkey -noout -out .secrets/jwt/private.pem
  openssl ec -in .secrets/jwt/private.pem -pubout -out .secrets/jwt/public.pem
  chmod +x mvnw
  ```

### 2. Levantar los servicios en Docker

```bash
docker compose --profile app up -d --wait
```

> **Puerto del portal (8080):** En Docker, el único puerto expuesto al host es el **8080** (Nginx / Portal), el cual enruta internamente hacia el Gateway (`:8082`) y los microservicios. Todas las pruebas lanzadas desde la máquina anfitriona deben apuntar a `-DbaseUrl=http://localhost:8080`.

---

### 3. Comprobación rápida de SLAs (JUnit 5 + AssertJ)

Estas pruebas (`PerformTest*.java`) verifican en segundos que la latencia p95, el throughput y la tasa de error (0%) cumplan con el SLA pactado.

#### Ejecutar todas las suites de rendimiento juntas:
* **Windows (PowerShell/CMD):**
  ```powershell
  .\mvnw.cmd test -Dtest="PerformTest*" -DbaseUrl=http://localhost:8080
  ```
* **Linux / macOS:**
  ```bash
  ./mvnw test -Dtest="PerformTest*" -DbaseUrl=http://localhost:8080
  ```

#### O ejecutar por módulo individual:
| Módulo | Comando en Windows | Comando en Linux / macOS |
|---|---|---|
| **gateway** | `.\mvnw.cmd -f gateway/pom.xml test -Dtest=PerformTestGateway -DbaseUrl=http://localhost:8080` | `./mvnw -f gateway/pom.xml test -Dtest=PerformTestGateway -DbaseUrl=http://localhost:8080` |
| **ms-ai** | `.\mvnw.cmd -f ms-ai/pom.xml test -Dtest=PerformTestMsAI -DbaseUrl=http://localhost:8080` | `./mvnw -f ms-ai/pom.xml test -Dtest=PerformTestMsAI -DbaseUrl=http://localhost:8080` |
| **ms-auth** | `.\mvnw.cmd -f ms-auth/pom.xml test -Dtest=PerformTestMsAuth -DbaseUrl=http://localhost:8080` | `./mvnw -f ms-auth/pom.xml test -Dtest=PerformTestMsAuth -DbaseUrl=http://localhost:8080` |
| **ms-debt** | `.\mvnw.cmd -f ms-debt/pom.xml test -Dtest=PerformTestMsDebt -DbaseUrl=http://localhost:8080` | `./mvnw -f ms-debt/pom.xml test -Dtest=PerformTestMsDebt -DbaseUrl=http://localhost:8080` |
| **ms-payments** | `.\mvnw.cmd -f ms-payments/pom.xml test -Dtest=PerformTestMsPayments -DbaseUrl=http://localhost:8080` | `./mvnw -f ms-payments/pom.xml test -Dtest=PerformTestMsPayments -DbaseUrl=http://localhost:8080` |

---

### 4. Simulaciones de carga y estrés con Gatling

Las simulaciones de Gatling generan tráfico concurrente y reportes HTML completos con histogramas de percentiles.

#### Prueba de humo rápida (verificación en ~15 segundos):
Para comprobar que Gatling funciona sin esperar la simulación completa de varios minutos, pasa parámetros reducidos:
* **Windows:**
  ```powershell
  .\mvnw.cmd -f gateway/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.gateway.troughput.LoadSimulationGateway" -DbaseUrl=http://localhost:8080 -Drampa=2 -Dduracion=3 -DusuariosMax=10
  ```
* **Linux / macOS:**
  ```bash
  ./mvnw -f gateway/pom.xml gatling:test -Dgatling.simulationClass=com.tbridge.gateway.troughput.LoadSimulationGateway -DbaseUrl=http://localhost:8080 -Drampa=2 -Dduracion=3 -DusuariosMax=10
  ```

#### Simulación de carga completa (Load):
* **Windows:**
  ```powershell
  .\mvnw.cmd -f gateway/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.gateway.troughput.LoadSimulationGateway" -DbaseUrl=http://localhost:8080
  ```
* **Linux / macOS:**
  ```bash
  ./mvnw -f gateway/pom.xml gatling:test -Dgatling.simulationClass=com.tbridge.gateway.troughput.LoadSimulationGateway -DbaseUrl=http://localhost:8080
  ```

#### Catálogo de simulaciones Gatling disponibles:

| Módulo | Simulación de Carga (`Load`) | Simulación de Estrés (`Stress`) | Picos (`Spike`) | Resistencia (`Soak`) |
|---|---|---|---|---|
| **gateway** | `com.tbridge.gateway.troughput.LoadSimulationGateway` | `com.tbridge.gateway.troughput.StressSimulationGateway` | `com.tbridge.gateway.troughput.SpikeSimulationGateway` | `com.tbridge.gateway.troughput.SoakSimulationGateway` |
| **ms-ai** | `com.tbridge.ai.troughput.LoadSimulationMsAI` | `com.tbridge.ai.troughput.StressSimulationMsAI` | `com.tbridge.ai.troughput.SpikeSimulationMsAI` | `com.tbridge.ai.troughput.SoakSimulationMsAI` |
| **ms-auth** | `com.tbridge.auth.troughput.LoadSimulationMsAuth` | — | — | — |
| **ms-debt** | `com.tbridge.debt.troughput.LoadSimulationMsDebt` | `com.tbridge.debt.troughput.StressSimulationMsDebt` | `com.tbridge.debt.troughput.SpikeSimulationMsDebt` | `com.tbridge.debt.troughput.SoakSimulationMsDebt` |
| **ms-payments**| `com.tbridge.payments.troughput.LoadSimulationMsPayments` | `com.tbridge.payments.troughput.StressSimulationMsPayments` | `com.tbridge.payments.troughput.SpikeSimulationMsPayments` | `com.tbridge.payments.troughput.SoakSimulationMsPayments` |

> Los reportes interactivos se generan automáticamente en: `<modulo>/target/gatling/<simulacion-fecha>/index.html`.

---

### 5. Ejecución interactiva desde Neovim (Neotest)

Si utilizas el editor Neovim con la configuración del proyecto:
1. Abre Neovim en la raíz del repositorio (`nvim .`).
2. Presiona **`tp`** (o **`<leader>tp`**) para abrir el panel resumen exclusivo de pruebas de rendimiento.
3. Desplázate con `j`/`k` sobre cualquier archivo o método de prueba y presiona **`r`** para ejecutarlo. Neotest inyectará automáticamente `-DbaseUrl=http://localhost:8080` y mostrará los resultados en vivo.
4. Para volver al resumen general con las 1207 pruebas unitarias, presiona **`<leader>ts`**.

---

### 6. Ejecución con Token de Deudor (Rutas protegidas)

Por defecto, si no se proporciona un token, las pruebas ejecutan las **rutas públicas** (`/api/ai/salud`). Para medir rutas protegidas por JWT (`/api/me`, `/api/debts`, `/api/payments`):

1. Solicita un código de acceso para un deudor de prueba en el portal:
   ```bash
   curl -X POST http://localhost:8080/api/auth/codigo -H "Content-Type: application/json" -d '{"identificador":"11.111.111-1"}'
   ```
2. Revisa el código de 6 caracteres en el buzón local de Mailpit: `http://localhost:8025`.
3. Canjea el código por un JWT:
   ```bash
   curl -X POST http://localhost:8080/api/auth/acceso -H "Content-Type: application/json" -d '{"identificador":"11.111.111-1","codigo":"<CODIGO>"}'
   ```
4. Pasa el token obtenido en la línea de comando:
   ```bash
   # JUnit:
   ./mvnw -f gateway/pom.xml test -Dtest=PerformTestGateway -DbaseUrl=http://localhost:8080 -Dtoken="<TOKEN_JWT>"

   # Gatling:
   ./mvnw -f gateway/pom.xml gatling:test -Dgatling.simulationClass=com.tbridge.gateway.troughput.LoadSimulationGateway -DbaseUrl=http://localhost:8080 -Dtoken="<TOKEN_JWT>"
   ```

---

## Antes de correrlas

La pila completa arriba, **con el límite de peticiones subido**:

```powershell
$env:RATE_GLOBAL_CAPACITY = "10000000"
docker compose --profile app up -d --wait
```

### Por qué se sube el límite

Todo el tráfico de k6 sale de **una sola IP**, y el limitador del gateway cuenta por IP. Con el
cupo de fábrica —120 por minuto— las pruebas que pasan por el gateway medirían el limitador y no el
sistema: dos peticiones por segundo y el resto `429`.

> **La trampa.** La variable vale solo en esa terminal, y **cualquier** `docker compose up`
> que se haga sin ella —aunque sea para reconstruir otro servicio— recrea el gateway con el cupo
> de fábrica. Le pasó a quien escribió estas pruebas: el resultado salió con 99 % de errores en
> medio milisegundo, que parece un sistema roto y era solo el limitador haciendo su trabajo.
>
> Por eso `debt-load.js`, `debt-stress.js`, `gateway-load.js` y `payment-load.js` **se detienen
> solos** si el limitador responde aunque sea una vez (`limitador_intervino`), en vez de entregar un
> número falso. Las pruebas que van directo a un servicio —`auth-load.js`, `payment-stress.js`,
> `ai-load.js` y `portal-load.js`— no pasan por el gateway, así que no necesitan la variable; las
> que necesitan sesión la arman en el `setup()` entrando por el portal.

Al terminar, el cupo vuelve a lo normal con:

```powershell
Remove-Item Env:\RATE_GLOBAL_CAPACITY
docker compose --profile app up -d gateway
```

---

## Gateway

### Enrutamiento: Gatling y JUnit / AssertJ

```powershell
# Simulación de carga sostenida con Gatling:
.\mvnw.cmd -f gateway/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.gateway.troughput.LoadSimulationGateway"

# Comprobación rápida de SLA con AssertJ / Surefire:
.\mvnw.cmd -f gateway/pom.xml test -Dtest=PerformTestGateway -DbaseUrl=http://localhost:8082
```

Una petición a cada familia de rutas, con la misma sesión de deudor, para ver si alguna se queda
pegada. Va directo a `gateway:8082`, sin nginx: lo que se aísla es el gateway. El camino completo
con nginx lo mide `ms-debt/debt-load.js`.

| Ruta | Va a | Qué cuesta |
| --- | --- | --- |
| `GET /api/me` | ms-auth | Verificar el JWT y devolver el usuario |
| `GET /api/debts` | ms-debt | La lista de deudas del deudor |
| `GET /api/payments` | ms-payments | El historial de pagos |
| `GET /api/ai/salud` | ms-ai | La salud del asistente (pública) |

Sube por escalones de 5, 20 y 50 usuarios. Cada ruta tiene su propio `Trend` (`gw_me`,
`gw_debts`, `gw_payments`, `gw_salud`) para que un servicio lento no quede escondido en el
promedio.

#### Resultado

> Medido el 04-11-2026 · misma pila local · límite subido a 10.000.000 y restaurado al terminar

| Ruta | p95 | Máximo |
| --- | --- | --- |
| `GET /api/me` | 37,14 ms | 257 ms |
| `GET /api/debts` | 83,73 ms | 5,15 s |
| `GET /api/payments` | 36,64 ms | 164 ms |
| `GET /api/ai/salud` | 32,75 ms | 136 ms |

**14.578 peticiones, 0 fallos y el limitador no intervino ni una vez.** Enrutar cuesta poco y el
reparto es parejo; la lista de deudas es la ruta más cara, como era de esperar porque es la que
toca MySQL. El máximo de 5,15 s es un pico aislado —el p95 se queda en 84 ms—, no un problema
sostenido.

---

## ms-auth

### Lecturas de sesión: Gatling y JUnit / AssertJ

```powershell
# Simulación de carga sostenida con Gatling:
.\mvnw.cmd -f ms-auth/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.auth.troughput.LoadSimulationMsAuth"

# Comprobación rápida de SLA con AssertJ / Surefire:
.\mvnw.cmd -f ms-auth/pom.xml test -Dtest=PerformTestMsAuth -DbaseUrl=http://localhost:8081
```

| Ruta | Qué cuesta |
| --- | --- |
| `GET /api/me` | Verificar el JWT y devolver quién soy |
| `GET /.well-known/jwks.json` | Entregar las llaves públicas con que los servicios verifican el JWT |

Va directo a `ms-auth:8081`. El acceso con código no se repite aquí: el código sirve una sola vez y
tiene su propia prueba. Sube por escalones de 5, 20, 50 y 100 usuarios.

#### Resultado

> Medido el 04-11-2026 · misma pila local · umbrales `p(95)<300` ms

| Ruta | p95 | Media |
| --- | --- | --- |
| `GET /api/me` | 4,84 ms | 2,65 ms |
| `GET /.well-known/jwks.json` | 2,43 ms | 1,13 ms |

**20.864 peticiones, 0 fallos.** Verificar el JWT y entregar las llaves públicas no se acerca al
umbral ni con cien usuarios: la sesión no es el cuello de botella.

### Los frenos contra la fuerza bruta: `auth-limit.js`

Con los valores de **fábrica**, sin la variable:

```powershell
docker compose --profile carga run --rm k6 run /performance-tests/k6/ms-auth/auth-limit.js
```

Son dos capas, y cada una responde `429` con su propio mensaje:

| Capa | Qué cuenta | Cuánto |
| --- | --- | --- |
| **Gateway** | Peticiones por IP a `/api/auth/**`, sin mirar si el código era correcto | 10 por minuto |
| **ms-auth** | **Intentos fallidos** por IP | 10 en diez minutos, y bloquea |

La que de verdad importa contra la fuerza bruta es la segunda: el cupo del gateway se recarga
cada seis segundos, así que solo, dejaría pasar un intento cada seis segundos para siempre.

La prueba manda códigos equivocados y comprueba las dos: que el gateway corte en la petición
11, que se recargue solo —un limitador que bloquea para siempre no es una protección sino una
caída—, y que la petición que logra pasar la frene `ms-auth`, porque ese origen ya falló diez
veces.

> **Ojo con el orden.** Al terminar, esta prueba deja la IP de k6 bloqueada en `ms-auth` durante
> diez minutos. Córrela al final, o espera antes de volver a pedir códigos para las demás pruebas.

#### Resultado

> Medido el 23-09-2026 · cupos de fábrica

| Comprobación | Resultado |
| --- | --- |
| El gateway corta en la petición 11 | ✓ |
| El cupo del gateway se recarga solo | ✓ |
| `ms-auth` frena por su cuenta al origen que ya falló diez veces | ✓ |

#### Lo que encontró: todos eran la misma IP

La primera versión de esta prueba falló de una forma que destapó un error grave. `ms-auth`
anotaba **todos** los intentos con la IP del **gateway**: Spring Cloud Gateway borra la cabecera
`X-Forwarded-For` antes de reenviar cuando nadie le dijo en qué proxies confiar, y es una defensa
razonable, pero dejaba a `ms-auth` sin saber quién era el cliente. Como su freno cuenta por IP,
**diez códigos equivocados de cualquier persona bloqueaban el ingreso con código de todos los
deudores durante diez minutos.** Una denegación de servicio al alcance de cualquiera.

Y al rastrearlo apareció un segundo problema: el gateway le creía el primer valor de esa
cabecera a cualquiera, y nginx agregaba la IP real al final de lo que mandara el cliente. Un
atacante que enviara un valor distinto en cada intento conseguía un cupo nuevo cada vez.

Los dos se cerraron juntos: nginx **sobrescribe** la cabecera con la IP que vio, el gateway solo
le cree a los proxies de su lista (`TRUSTED_PROXIES`) y se la pasa a los servicios, y el
limitador aplica la misma regla. Está comprobado con tráfico real: mandando doce IP inventadas
el sistema igual frena en la décima, y los fallos de un cliente ya no bloquean a otro. Las
reglas tienen además sus pruebas unitarias en `gateway/src/test`.

---

## ms-debt

### Capacidad: Gatling y JUnit / AssertJ

```powershell
# Simulación de carga sostenida con Gatling:
.\mvnw.cmd -f ms-debt/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.debt.troughput.LoadSimulationMsDebt"

# Comprobación rápida de SLA con AssertJ / Surefire:
.\mvnw.cmd -f ms-debt/pom.xml test -Dtest=PerformTestMsDebt -DbaseUrl=http://localhost:8083
```

Sube por escalones —5, 20, 50 y 100 usuarios simultáneos, medio minuto sostenido en cada uno—
sobre las cuatro lecturas que de verdad se usan. Cada usuario pide cuatro páginas y se queda un
segundo mirando, como haría una persona.

| Ruta | Qué cuesta |
| --- | --- |
| `GET /api/debts` | La lista del deudor: join contra la vista de saldo |
| `GET /api/debts/{id}` | El detalle de una deuda con sus cargos |
| `GET /api/debts/{id}/simulate` | El simulador de cuotas, entre 3 y 24 |
| `GET /api/analytics/summary` | El resumen de la empresa: la lectura más pesada |

**Solo lecturas, a propósito.** Un minuto de carga sostenida sobre *repactar* o *pagar* dejaría
miles de convenios basura en la base, y la prueba dejaría de poder repetirse.

La sesión del deudor se arma en el `setup()`: se pide un código por la red interna y se canjea.
La de la empresa se pide por correo y se lee del buzón de prueba, que es el mismo camino que
recorre una persona; si no se consigue, el escenario sigue sin medir el resumen y lo avisa.

#### Resultado

> Medido el 23-09-2026 sobre una pila recién levantada con volúmenes vacíos · Intel Core
> i5-14400F, con 8 CPU y 10 GB asignados a Docker Desktop en Windows · límite subido a
> 10.000.000 · los números son de los **tramos sostenidos** de cada escalón, no de las rampas.

| Usuarios | Peticiones/s | p50 | p95 | p99 | Errores |
| --- | --- | --- | --- | --- | --- |
| 5 | 19,4 | 5,0 ms | 7,6 ms | 9,6 ms | 0 % |
| 20 | 78,5 | 4,1 ms | 6,2 ms | 7,6 ms | 0 % |
| 50 | 196,4 | 4,3 ms | 6,9 ms | 9,1 ms | 0 % |
| 100 | 392,5 | 4,1 ms | 7,0 ms | 9,6 ms | 0 % |

En el escalón de 100 usuarios, por ruta:

| Ruta | p95 |
| --- | --- |
| `GET /api/debts` | 5,8 ms |
| `GET /api/debts/{id}` | 6,9 ms |
| `GET /api/debts/{id}/simulate` | 5,2 ms |
| `GET /api/analytics/summary` | 8,2 ms |

**32.500 peticiones, todas `200`**, y el limitador no intervino ni una vez.

**Lectura de los números.** El rendimiento crece en línea recta con los usuarios —unas cuatro
peticiones por segundo cada uno— y el p95 no se mueve de los 6 a 8 ms. Dicho de otro modo: **a
cien personas a la vez, el sistema no se entera**. El primer escalón sale apenas más lento que
los siguientes porque ahí la JVM todavía se está calentando. Esta prueba no alcanza a mostrar
dónde se quiebra el sistema, y por eso existe la siguiente.

#### Después de la refactorización (24-09-2026)

La misma prueba y las mismas condiciones —pila recién levantada con volúmenes vacíos, tramos
sostenidos— después de pasar el backend a DTOs con tipo, HATEOAS y Actuator:

| Usuarios | Peticiones/s | p50 | p95 | p99 | Errores |
| --- | --- | --- | --- | --- | --- |
| 5 | 18,9 | 14,3 ms | 22,3 ms | 27,6 ms | 0 % |
| 20 | 77,5 | 8,0 ms | 14,1 ms | 19,6 ms | 0 % |
| 50 | 194,5 | 6,2 ms | 10,9 ms | 15,3 ms | 0 % |
| 100 | 388,9 | 6,1 ms | 11,8 ms | 18,7 ms | 0 % |

**32.132 peticiones, todas `200`.** Salió más lento que el 23-09, así que en vez de suponer la
causa se midió cada pieza **lado a lado con la versión anterior**, en la misma máquina y a la
misma hora: 300 peticiones por segundo, con la JVM ya caliente.

| Qué se midió | Antes | Después |
| --- | --- | --- |
| ms-debt solo, lista (mediana / p95) | 3,6 / 6,8 ms | 3,7 / 7,1 ms |
| ms-debt solo, detalle | 4,3 / 7,8 ms | 4,3 / 7,9 ms |
| A través del gateway, lista | 4,8 / 8,9 ms | 5,2 / 9,6 ms |
| A través del gateway, detalle | 5,7 / 9,8 ms | 6,0 / 10,7 ms |

**Lectura.** Los DTOs y HATEOAS no encarecieron el servicio: ms-debt mide lo mismo, dentro del
ruido. El gateway nuevo suma unos 0,3 ms por petición, y no es el Swagger (apagado da lo mismo).
El resto de la diferencia con el 23-09 es de la máquina, no del código: ese día el sistema
completo medía menos que hoy el servicio solo con el código anterior. Por eso una comparación de
rendimiento se hace lado a lado y no contra un número de otro día.

### Estrés: Gatling

```powershell
# Simulación de estrés hacia el límite con Gatling:
.\mvnw.cmd -f ms-debt/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.debt.troughput.StressSimulationMsDebt"
```

La prueba de capacidad no alcanza a mostrar el techo: con las pausas de una persona, cien
usuarios son apenas cuatrocientas peticiones por segundo, y ahí la latencia no se mueve.

Este escenario no simula a nadie. **Impone un ritmo** de peticiones por segundo, sin pausas, y lo
sube por escalones —200, 500, 1.000, 1.500 y 2.000— hasta que algo cede: la latencia se dispara,
aparecen errores, o k6 ya no alcanza a sostener el ritmo pedido porque las respuestas no vuelven a
tiempo (las **iteraciones perdidas**). Una sola ruta, la más usada —la lista de deudas—, para
que el número sea del sistema y no de una mezcla.

Tiene un freno de mano: si el p95 pasa de dos segundos, se detiene, porque el techo ya se
encontró y seguir solo recalentaría la máquina.

#### Resultado

> Medido el 23-09-2026 · la misma máquina y la misma pila · límite subido a 10.000.000

| Ritmo pedido | Logrado | p50 | p95 | p99 | Errores | Perdidas |
| --- | --- | --- | --- | --- | --- | --- |
| 200/s | 200/s | 3,8 ms | 5,8 ms | 7,3 ms | 0 % | 0 |
| 500/s | 500/s | 3,1 ms | 5,4 ms | 7,8 ms | 0 % | 0 |
| 1.000/s | 999/s | 3,6 ms | 10,5 ms | 34,9 ms | 0 % | 34 |
| 1.500/s | 1.490/s | 5,7 ms | **77,2 ms** | 171,2 ms | 0 % | 308 |
| 2.000/s | **1.806/s** | 158,1 ms | **405,6 ms** | 592,8 ms | 0 % | 6.195 |

**211.577 peticiones, todas respondidas con `200`.** Holgado hasta mil por segundo; hacia las
**1.500 empieza a doler** —el p95 se multiplica por siete—; y el **techo está en unas 1.800 por
segundo**, donde ya no alcanza a sostener lo que se le pide. Y aun saturado **no falla**: se pone
lento, que es como tiene que degradar un sistema.

#### Lo que encontró: nginx se quedaba sin puertos

La primera vez que corrió, esta prueba dio un resultado muy distinto: **`502` desde las 500
peticiones por segundo**, con la latencia bajísima. Un sistema saturado se pone lento *antes* de
fallar; errores rápidos significan que algo rechaza de inmediato.

Era nginx. Abría una conexión TCP **nueva** hacia el gateway por cada petición y la cerraba al
terminar, y cada conexión cerrada deja su puerto ocupado sesenta segundos. Pasadas las quinientas
por segundo se acababan los puertos, y nginx respondía `connect() failed (99: Address not
available)`. **El gateway y los servicios no registraron un solo error**: estaban bien.

El arreglo está en [`frontend/nginx.conf.template`](../../frontend/nginx.conf.template): un bloque `upstream` con
`keepalive`, para que las conexiones se reutilicen. Con eso los `502` desaparecieron y el techo
pasó de ~500 a ~1.800 peticiones por segundo.

---

## ms-payments

### Abrir cobros: `payment-load.js`

Lo que pasa cuando muchos deudores tocan *Pagar* a la vez: abrir el cobro (nginx → gateway →
ms-payments, que le pregunta el monto a ms-debt y lo guarda en MySQL) y la consulta que repite el
portal mientras espera a la pasarela. Cada deudor abre un cobro en una pasarela al azar, pregunta
dos veces en qué va y descansa dos segundos.

**Solo contra las pasarelas simuladas.** Los ambientes de prueba de Transbank, Khipu y Mercado Pago
son compartidos y no se les hace carga. Antes de empezar, la prueba abre un cobro en cada pasarela
y **se detiene** si alguno sale a una pasarela de verdad. Para correrla, ms-payments con las tres
simuladas y el límite subido, solo en esa terminal:

```powershell
$env:TRANSBANK_ENVIRONMENT = "SIMULADA"; $env:KHIPU_LLAVE = ""; $env:MERCADOPAGO_ACCESS_TOKEN = ""
$env:RATE_GLOBAL_CAPACITY = "10000000"
docker compose --profile app up -d --wait ms-payments gateway
# Simulación de carga sostenida con Gatling:
.\mvnw.cmd -f ms-payments/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.payments.troughput.LoadSimulationMsPayments"

# O comprobación rápida de SLA con AssertJ / Surefire:
.\mvnw.cmd -f ms-payments/pom.xml test -Dtest=PerformTestMsPayments -DbaseUrl=http://localhost:8084
```

Con `-e MAX_VUS=100` el último escalón llega a cien deudores. No confirma los cobros (pagarlos
dejaría la deuda en cero a mitad de la medición): quedan abiertos y simulados, y al final la prueba
imprime los `DELETE` que los borran. Después, se vuelve a lo normal:

```powershell
Remove-Item Env:\TRANSBANK_ENVIRONMENT, Env:\KHIPU_LLAVE, Env:\MERCADOPAGO_ACCESS_TOKEN, Env:\RATE_GLOBAL_CAPACITY
docker compose --profile app up -d --wait ms-payments gateway
```

#### Resultado (7 de octubre de 2026)

| Último escalón | Peticiones | Fallidas | Abrir el cobro (p95) | Consultarlo (p95) | Cobros por segundo |
| --- | --- | --- | --- | --- | --- |
| 20 deudores | 1.161 | **0** | 37 ms | 13 ms | 2,6 |
| 100 deudores | 5.817 | **0** | 30 ms | 7 ms | 13 |

Abrir un cobro cuesta unos 25 ms y no crece con la carga: el tiempo de un pago de verdad está en
la pasarela, no en DataBridge. El limitador no intervino en ninguna de las dos.

### Leer el historial: Gatling

```powershell
# Simulación de estrés hacia el límite con Gatling:
.\mvnw.cmd -f ms-payments/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.payments.troughput.StressSimulationMsPayments"
```

La otra mitad de ms-payments: el historial que el portal pide cada vez que alguien entra a *Mis
pagos*. Impone un ritmo de peticiones por segundo sobre `GET /api/payments`, sin pausas, y lo sube
por escalones —200, 500, 1.000 y 1.500— hasta que la latencia se dispara o k6 ya no sostiene el
ritmo. Es solo lectura: no abre cobros ni confirma pagos, así que se puede repetir sin dejar basura
en la base. Si el p95 pasa de dos segundos, se detiene.

#### Resultado

> Medido el 04-11-2026 · misma pila local · freno de mano en `p(95)<2.000` ms

| Peticiones | Fallidas | p50 | p95 | p99 | Iteraciones perdidas |
| --- | --- | --- | --- | --- | --- |
| 121.967 | **0** | 9,75 ms | 771 ms | — | 11.161 |

**Lectura.** El servicio no falló ni una vez y el p95 se quedó por debajo del freno de mano, pero
las iteraciones perdidas dicen que en los escalones altos k6 ya no alcanzaba a pedir todo el ritmo:
el techo de peticiones sostenidas está antes de las 1.500 por segundo. La mediana de 9,75 ms muestra
que el camino normal sigue siendo barato; lo que crece es la cola cuando la máquina se satura.

---

## ms-ai

### El asistente: Gatling y JUnit / AssertJ

```powershell
# Simulación de carga sostenida con Gatling:
.\mvnw.cmd -f ms-ai/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.ai.troughput.LoadSimulationMsAI"

# Comprobación rápida de SLA con AssertJ / Surefire:
.\mvnw.cmd -f ms-ai/pom.xml test -Dtest=PerformTestMsAI -DbaseUrl=http://localhost:8085

# Simulación de estrés con Gatling:
.\mvnw.cmd -f ms-ai/pom.xml gatling:test "-Dgatling.simulationClass=com.tbridge.ai.troughput.StressSimulationMsAI"
```

| Ruta | Qué cuesta |
| --- | --- |
| `GET /api/ai/salud` | Si el servicio está arriba y qué proveedor usa |
| `POST /api/ai/chat` | Una pregunta del deudor: ms-ai lee su cartera en ms-debt con el mismo JWT |

Con `LLM_PROVIDER=local` responde con reglas y no sale a internet; con Groq, Gemini o xAI la
latencia la pone el proveedor y el umbral deja de ser comparable. Sube por escalones de 5, 10 y 20
usuarios, con una pausa de un segundo entre preguntas.

#### Resultado

> Medido el 04-11-2026 · `LLM_PROVIDER=local` · umbrales `ai_salud<500` ms y `ai_chat<1.500` ms

| Ruta | p95 | Media |
| --- | --- | --- |
| `GET /api/ai/salud` | 7,82 ms | 3,20 ms |
| `POST /api/ai/chat` | 58,77 ms | 29,69 ms |

**2.040 peticiones, 0 fallos.** Cada pregunta incluye la lectura de la cartera en ms-debt y aun así
queda muy por debajo del umbral. Con un LLM real este número no sirve para comparar: la latencia
pasa a depender del proveedor.

---

## portal

### Archivos estáticos: `portal-load.js`

```powershell
docker compose --profile carga run --rm k6 run /performance-tests/k6/portal/portal-load.js
```

El portal como servicio propio: nginx con el build de React. No toca la API.

| Ruta | Qué cuesta |
| --- | --- |
| `GET /` | La portada con el index |
| `GET /assets/...` | El JS y el CSS del build, con caché de un año |
| `GET /app`, `/login`, ... | Rutas de React: nginx devuelve el mismo index |

Los nombres de los assets se leen del index en el `setup()`, porque el build de Vite les pone un
hash que cambia en cada compilación. Sube por escalones de 10, 50 y 100 usuarios.

#### Resultado

> Medido el 04-11-2026 · misma pila local · umbral `p(95)<300` ms

| Ruta | p95 | Media |
| --- | --- | --- |
| `GET /` | 814 µs | 509 µs |
| `GET /assets/...` | 1,42 ms | 676 µs |
| Rutas de React | 731 µs | 409 µs |

**38.296 comprobaciones, todas en verde, y 0 fallos HTTP.** Servir los archivos no cuesta nada
medible: el portal no aparece como cuello de botella ni con cien usuarios.

---

## Lo que estas pruebas no dicen

- Se corren sobre **Docker Desktop en una máquina de escritorio**, con las tres bases, el bus de
  mensajes y los cinco servicios compitiendo por los mismos núcleos. Los números sirven para
  comparar entre escalones y para detectar una regresión, **no para prometer capacidad en
  producción**.
- No miden confirmar pagos ni repactar. `payment-load.js` mide abrir cobros, que sí escribe, pero
  confirmarlos cambia el saldo, y medirlo con carga sostenida exigiría poder deshacer lo escrito.
- No miden la base con volumen real. La cartera de prueba tiene unas pocas deudas: una tabla con
  cien mil se comportaría distinto, y eso es una prueba aparte que todavía no existe.
- Los escenarios nuevos —`gateway-load.js`, `auth-load.js`, `payment-stress.js`, `ai-load.js` y
  `portal-load.js`— ya tienen su corrida registrada arriba, el 04-11-2026. Los números de
  `debt-load.js`, `debt-stress.js`, `payment-load.js` y `auth-limit.js` son de las corridas
  originales, que se conservaron sin cambios en sus escalones para poder compararlos.
