# <picture><source media="(prefers-color-scheme: dark)" srcset="frontend/public/marca/simbolo-oscuro.svg"><img src="frontend/public/marca/simbolo-claro.svg" alt="DataBridge" width="40" height="40" align="top"></picture> DataBridge

[![gateway](https://github.com/TechnicalBridge/DataBridge/actions/workflows/gateway.yml/badge.svg?branch=main)](https://github.com/TechnicalBridge/DataBridge/actions/workflows/gateway.yml) [![ms-auth](https://github.com/TechnicalBridge/DataBridge/actions/workflows/ms-auth.yml/badge.svg?branch=main)](https://github.com/TechnicalBridge/DataBridge/actions/workflows/ms-auth.yml) [![ms-debt](https://github.com/TechnicalBridge/DataBridge/actions/workflows/ms-debt.yml/badge.svg?branch=main)](https://github.com/TechnicalBridge/DataBridge/actions/workflows/ms-debt.yml) [![ms-payments](https://github.com/TechnicalBridge/DataBridge/actions/workflows/ms-payments.yml/badge.svg?branch=main)](https://github.com/TechnicalBridge/DataBridge/actions/workflows/ms-payments.yml) [![frontend](https://github.com/TechnicalBridge/DataBridge/actions/workflows/frontend.yml/badge.svg?branch=main)](https://github.com/TechnicalBridge/DataBridge/actions/workflows/frontend.yml) [![ms-ai](https://github.com/TechnicalBridge/DataBridge/actions/workflows/ms-ai.yml/badge.svg?branch=main)](https://github.com/TechnicalBridge/DataBridge/actions/workflows/ms-ai.yml) [![codeql](https://github.com/TechnicalBridge/DataBridge/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/TechnicalBridge/DataBridge/actions/workflows/codeql.yml)

**La plataforma donde el deudor en cobranza paga, repacta o reclama su deuda, sin llamar a nadie**, sea del rubro que sea: arriendos, aranceles, tratamientos, planes mensuales. Proyecto de Capstone del equipo **Technical Bridge**; las empresas, las personas y los RUT son ficticios.

---

## Indice

- [1. Nombre del proyecto](#1-nombre-del-proyecto)
- [2. Descripcion](#2-descripcion)
  - [Que hace](#que-hace)
  - [A quien va dirigido](#a-quien-va-dirigido)
  - [Que problema resuelve](#que-problema-resuelve)
- [3. Tecnologias utilizadas](#3-tecnologias-utilizadas)
  - [Lenguajes](#lenguajes)
  - [Frameworks y librerias](#frameworks-y-librerias)
  - [Base de datos](#base-de-datos)
  - [Cloud y servicios externos](#cloud-y-servicios-externos)
- [4. Instrucciones para ejecutar el proyecto localmente](#4-instrucciones-para-ejecutar-el-proyecto-localmente)
  - [Requisitos previos](#requisitos-previos)
  - [Paso a paso en Docker](#paso-a-paso-en-docker)
  - [Servicios y URLs principales](#servicios-y-urls-principales)
  - [Cuentas de prueba y demostracion](#cuentas-de-prueba-y-demostracion)
- [5. Integrantes del equipo con sus roles](#5-integrantes-del-equipo-con-sus-roles)
- [6. Metodologia de trabajo del equipo](#6-metodologia-de-trabajo-del-equipo)
  - [Marco de trabajo: Scrumban](#marco-de-trabajo-scrumban)
  - [Practicas DevOps y aseguramiento de calidad](#practicas-devops-y-aseguramiento-de-calidad)
  - [Pruebas automatizadas (1.320 pruebas Java)](#pruebas-automatizadas-1320-pruebas-java)
- [7. Arquitectura de la solucion](#7-arquitectura-de-la-solucion)
  - [Descripcion de la arquitectura](#descripcion-de-la-arquitectura)
  - [Diagrama general de componentes y comunicacion](#diagrama-general-de-componentes-y-comunicacion)
  - [Diagrama de despliegue fisico](#diagrama-de-despliegue-fisico)
  - [Diagrama de secuencia del flujo principal](#diagrama-de-secuencia-del-flujo-principal)
  - [Diagrama de casos de uso](#diagrama-de-casos-de-uso)
  - [Diagramas entidad-relacion (ER)](#diagramas-entidad-relacion-er)
  - [Detalle de arquitectura en vistas 4+1](#detalle-de-arquitectura-en-vistas-41)
- [Documentacion complementaria](#documentacion-complementaria)

---

## 1. Nombre del proyecto

**DataBridge** — Plataforma web de autoservicio y gestion integral de cobranza temprana y regularizacion de deudas morosas.

---

## 2. Descripcion

### Que hace

**DataBridge es una plataforma de autoservicio para la gestion y regularizacion de deudas morosas.** Permite al deudor acceder sin contrasenas convencionales utilizando su RUT y un codigo de seis caracteres enviado a su correo electronico. Desde la plataforma puede:

- **Pagar de una vez**: mediante pasarelas de pago digitales integradas (Webpay Plus de Transbank, Mercado Pago Checkout Pro o Khipu).
- **Repactar en cuotas**: simular y pactar convenios de 3 a 24 cuotas, con calculo transparente del costo total y Carga Anual Equivalente (CAE), pudiendo pagar cuotas de forma individual o agrupada desde la que vence primero.
- **Reclamar cobros no correspondientes**: impugnar una deuda si no la reconoce, ya fue pagada o presenta inconsistencias. Mientras la empresa revisa la situacion, la cobranza y los recordatorios quedan suspendidos.
- **Consultar a un asistente inteligente**: resolver dudas mediante un asistente que consulta su deuda en tiempo real con su propia sesion autenticada.
- **Descargar comprobantes y certificados**: emitir certificados de deuda pagada (deuda cero) y comprobantes de pago individuales en PDF.

Para las **empresas que gestionan la cartera** (agencias de cobranza como APOFYX o acreedores directos), el portal ofrece:

- **Carga de cartera por campanas**: ingesta masiva mediante planilla Excel con validacion previa de inconsistencias antes de confirmar.
- **Cumplimiento legal de contacto**: ejecucion automatizada de contactos respetando los limites de frecuencia y horarios que estipula la ley chilena (Ley 19.496).
- **Gestion integral**: resolucion de reclamos, seguimiento de convenios en riesgo (con cuotas vencidas), reenvio de codigos de acceso y panel analitico de recuperacion y calculo de comisiones.

### A quien va dirigido

| Actor | Rol en DataBridge |
| :--- | :--- |
| **La persona que debe (Deudor)** | Usuario principal del portal publico. Accede con su RUT y codigo temporal para consultar sus montos, repactar, pagar en linea o reclamar. |
| **La empresa que cobra (Agencia o Acreedor directo)** | Gestiona sus campanas de cobranza, carga carteras masivas, reenvia codigos de acceso, resuelve impugnaciones y analiza la recaudacion en el portal de empresas. |
| **El acreedor final (Inmobiliaria, instituto, clinica, etc.)** | Cuando delega la cobranza en una agencia, puede recibir en su sistema notificaciones firmadas via webhook (`/api/v1`) ante cada pago o convenio si la integracion API esta activa. |
| **Equipo de administracion DataBridge (`admin`)** | Habilita empresas usuarias y sus colaboradores, supervisa la bitacora de auditoria global (`admin_log`, `access_log`) y monitorea la salud de los servicios. |

### Que problema resuelve

- **Costo desproporcionado de la cobranza tradicional**: La gestion de deudas menores mediante canales telefonicos o cartas postales suele tener un costo operativo superior al margen recuperable, provocando abandono o castigo de cartera.
- **Barreras para el deudor que desea ponerse al dia**: La cobranza convencional impone esperas telefonicas en horarios de oficina restrictivos y carece de mecanismos transparentes para simular pagos accesibles o impugnar cobros erroneos.
- **Solucion integral**: DataBridge digitaliza el proceso en ambas direcciones: el deudor opera 24/7 con autonomia, mientras la empresa recauda y concilia automaticamente sin intervencion manual.

---

## 3. Tecnologias utilizadas

### Lenguajes

| Lenguaje | Version | Uso en la solucion |
| :--- | :--- | :--- |
| **Java** | 25 | Backend completo: gateway y los microservicios ms-auth, ms-debt, ms-payments y ms-ai. |
| **JavaScript** | ES2022 con JSX | Frontend React del portal (deudor, empresas y panel de administracion). |
| **SQL** | MySQL 8.4 | Definicion de esquemas relacionales, vistas y migraciones versionadas. |

### Frameworks y librerias

| Capa / Componente | Tecnologia | Proposito |
| :--- | :--- | :--- |
| **Backend** | Spring Boot 3.5 · Spring Security · Spring Data JPA · Bean Validation | Microservicios de negocio modulares y APIs REST seguras. |
| **Gateway & Resiliencia** | Spring Cloud Gateway · Bucket4j | Puerta de entrada unica: enrutamiento, validacion CORS y rate limiting distribuido por IP. |
| **Frontend** | React 18 · React Router 7 · Vite · Zustand · Recharts | SPA responsiva con diseno CSS nativo modular. |
| **Seguridad & Autenticacion** | JWT con **ES256 (ECDSA P-256)** · spring-security-oauth2-jose · JWKS | Sesiones sin contrasenas; firmas criptograficas asimetricas y verificacion distribuida via `/.well-known/jwks.json`. |
| **Cifrado & Hashing** | SHA-256 · AES-256-GCM (JCA) | Almacenamiento seguro de hashes para credenciales efimeras e IPs; cifrado en reposo para secretos de suscripcion. |
| **Mensajeria Asincrona** | RabbitMQ 3.13 (AMQP 0-9-1) | Propagacion asincrona desacoplada de avisos de pago (`pago.confirmado`). |
| **Asistente Inteligente** | SDK oficial OpenAI para Java (`openai-java`) | Consultas LLM compatibles con Groq y Gemini, con fallback automatico a motor de reglas local (`MotorLocal`). |
| **Documentacion & Hipermedios** | springdoc-openapi (Swagger) · Spring HATEOAS | Documentacion interactiva OpenAPI 3 y enlaces hipermedia en respuestas REST. |
| **Plantillas y Correo** | Jakarta Mail · Thymeleaf | Renderizado de correos transaccionales en HTML responsivo y texto plano. |

### Base de datos

- **MySQL 8.4 (*Database-per-service*)**: Aislamiento estricto de datos con tres bases independientes:
  - `tb_auth`: Codigos de acceso hasheados, enlaces magicos, sesiones, usuarios de personal y bitacora de accesos.
  - `tb_debt`: Empresas, mandatos, campanas, deudores, deudas, cargos, convenios, contactos y outbox de eventos.
  - `tb_payments`: Transacciones de pago, eventos de pasarelas, avisos de conciliacion y valores historicos de la UF.
- **Flyway**: Versionamiento ordenado de esquemas relacionales con validacion automatica al inicio (`ddl-auto: validate`).

### Cloud y servicios externos

| Servicio / Proveedor | Proposito | Alternativa / Modo local |
| :--- | :--- | :--- |
| **Docker & Docker Compose** | Orquestacion local con perfiles `app` y `carga`, y archivo `docker-compose.prod.yml` para produccion. | Despliegue en contenedores locales aislados. |
| **Transbank (Webpay Plus REST v1.2)** | Pagos con tarjeta de debito, credito y prepago. | Modo simulacion local (`TRANSBANK_ENVIRONMENT=SIMULADA`). |
| **Khipu (API v3)** | Cobro directo via transferencia electronica bancaria. | Simulacion automatica sin credenciales configuradas. |
| **Mercado Pago (Checkout Pro)** | Cobros con billetera digital y tarjetas. | Simulacion automatica sin token configurado. |
| **Banco Central de Chile (`si3.bcentral.cl`)** | Obtencion en linea del valor diario oficial de la UF. | Carga estatica manual de UF en base de datos. |
| **Brevo API / Servidores SMTP** | Despacho de correos electronicos transaccionales. | Buzon de correo local Mailpit (http://localhost:8025). |
| **Groq / Google Gemini** | Modelos de lenguaje natural para el asistente de deudas. | Motor local de reglas (`MotorLocal`) sin requerir API keys. |

---

## 4. Instrucciones para ejecutar el proyecto localmente

### Requisitos previos

- **Docker Desktop** en ejecucion con al menos 4 GB de memoria RAM asignados.
- Conexion a internet la primera vez para la descarga de dependencias e imagenes base.

### Paso a paso en Docker

```powershell
# 1. Clonar el repositorio
git clone https://github.com/TechnicalBridge/DataBridge.git
cd DataBridge

# 2. Generar el par de claves criptograficas JWT ES256 (una sola vez)
powershell -ExecutionPolicy Bypass -File .\scripts\generar-claves-jwt.ps1

# 3. Generar el archivo .env con contrasenas seguras aleatorias (una sola vez)
powershell -ExecutionPolicy Bypass -File .\preparar-env.ps1

# 4. (Opcional) Para cargar datos de demostracion en una base nueva:
# Asegurar DEMO_DATOS=true en el archivo .env

# 5. Levantar todos los contenedores y esperar a que esten en estado saludable
docker compose --profile app up -d --build --wait
```

> **Nota:** La bandera `--wait` retorna el control a la terminal unicamente cuando los once contenedores estan completamente inicializados y saludables (*healthy*). Para pruebas de carga se utiliza `--profile carga`, y para produccion el archivo `docker-compose.prod.yml`.

### Servicios y URLs principales

| Servicio | URL | Descripcion y Credenciales |
| :--- | :--- | :--- |
| **Portal Web** | [http://localhost:8080](http://localhost:8080) | Interfaz unificada de deudores, empresas y administracion. |
| **Documentacion OpenAPI / Swagger** | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | Catalogo interactivo de endpoints de los microservicios. |
| **Buzon de prueba (Mailpit)** | [http://localhost:8025](http://localhost:8025) | Interfaz web para consultar los correos y codigos de acceso emitidos. |
| **Consola RabbitMQ** | [http://localhost:15672](http://localhost:15672) | Usuario: `guest`, contrasena: valor de `RABBIT_PASSWORD` en `.env`. |
| **Bases de datos MySQL** | `127.0.0.1:3308` (`tb_auth`)<br>`127.0.0.1:3309` (`tb_debt`)<br>`127.0.0.1:3310` (`tb_payments`) | Usuario: `tbridge`, contrasenas configuradas en `.env`. |

Para apagar el entorno:
```powershell
docker compose --profile app down
# Para detener y reiniciar con base de datos limpia:
docker compose --profile app down -v
```

### Cuentas de prueba y demostracion

Con `DEMO_DATOS=true`:
- **Acceso Empresa**: Ingresar a *Soy de una empresa* con el correo `camila.reyes@example.com`. El codigo de acceso de un solo uso llegara al buzon Mailpit (http://localhost:8025).
- **Acceso Deudor**: Ingresar en *Tengo un codigo de acceso* o mediante solicitud de codigo con RUTs de ejemplo:
  - `16.482.337-7`: Deudor en convenio de 6 cuotas (3 pagadas).
  - `19.230.418-0`: Deudor con totalidad de deuda saldada.
  - `14.583.206-3`: Deudor con reclamo ingresado por monto disputado.
  - `76.991.245-2`: Empresa deudora con deuda pactada en UF.

Para ver el recorrido guiado completo de demostracion, consultar [docs/demo/recorrido-demostracion.md](docs/demo/recorrido-demostracion.md).

---

## 5. Integrantes del equipo con sus roles

| Integrante | Rol | Responsabilidades clave |
| :--- | :--- | :--- |
| **Flavio Henriquez** | Product Owner | Priorizacion del backlog, redaccion de historias de usuario con criterios de aceptacion y aprobacion de paso a *Ready*. |
| **Martin Gutierrez** | Scrum Master | Facilitacion del flujo continuo, gestion de impedimentos, control del WIP y facilitacion de ceremonias de retrospectiva. |
| **Esteban Maino** | Desarrollador fullstack y Project Coordinator | Coordinacion del proyecto y desarrollo en Spring Boot y React. |
| **Pedro Campos** | Desarrollador fullstack y QA Tester | Desarrollo integral en backend y frontend, diseno y ejecucion de pruebas automatizadas y aseguramiento de calidad. |

---

## 6. Metodologia de trabajo del equipo

### Marco de trabajo: Scrumban

El equipo adopta **Scrumban**, combinando la estructura de gobierno y roles de **Scrum** con la entrega de valor continua y control de flujo de **Kanban**:

- **Roles Scrum definidos**:
  - **Product Owner (Flavio Henriquez)**: Prioriza el backlog, escribe los criterios de aceptacion y decide que pasa a *Ready*.
  - **Scrum Master (Martin Gutierrez)**: Cuida que el trabajo fluya, que nadie exceda el limite de tarjetas en curso y abre las retrospectivas.
  - **Project Coordinator y Desarrollador Fullstack (Esteban Maino)**: Programa en backend y portal, y coordina el avance del proyecto.
  - **QA Tester y Desarrollador Fullstack (Pedro Campos)**: Programa en backend y portal, e implementa y valida las pruebas automatizadas.
- **Flujo Kanban sin Sprints fijos**: Las tareas avanzan de forma continua a traves del tablero Kanban de GitHub Projects a traves de las columnas: *Backlog → Ready → In Progress → In Review → Done*.
- **Control de WIP (Work In Progress)**: Limite estricto de maximo 2 tarjetas simultaneas en *In Progress* por persona para minimizar cambios de contexto y reducir los tiempos de ciclo (*lead time*).
- **Trunk-Based Development y Ramas cortas**: Desarrollo sobre `main` a traves de ramas cortas (`tipo/numero-descripcion`), integradas mediante Pull Requests atomicos con revision de codigo cruzada y aprobacion de CI.
- **Definition of Done (DoD)**: Todo Pull Request debe cumplir con verificacion de pruebas automatizadas en verde, analisis de seguridad sin vulnerabilidades, documentacion al dia y validacion funcional antes de integrarse.

### Practicas DevOps y aseguramiento de calidad

| Practica DevOps | Implementacion en DataBridge | Beneficio |
| :--- | :--- | :--- |
| **Integracion Continua (CI)** | GitHub Actions en cada push y PR (flujos para cada microservicio, frontend y gateway). | Garantiza que todo cambio compila y pasa la suite completa de pruebas en entornos limpios e independientes. |
| **Analisis Estatico y Seguridad** | CodeQL y Gitleaks integrados en los flujos de CI. | Deteccion preventiva de vulnerabilidades de codigo y prevencion de filtracion de secretos. |
| **Infraestructura como Codigo (IaC)** | Docker Compose orquestando 11 contenedores con perfiles `app` y `carga`, mas `docker-compose.prod.yml`. | Despliegue determinista y replicable identico en local y produccion. |
| **Evolucion de Base de Datos** | Flyway ejecutando migraciones SQL ordenadas y versionadas. | Eliminacion de desfases entre esquemas de base de datos y validacion al inicio (`ddl-auto: validate`). |

### Pruebas automatizadas (1.320 pruebas Java)

El proyecto cuenta con **1.320 pruebas automatizadas en Java**, medidas con JDK 25 mediante los reportes XML de Maven Surefire, que se ejecutan sin fallos en los flujos de CI de GitHub Actions:

```powershell
# Ejecutar las pruebas de un servicio especifico:
.\mvnw.cmd -f ms-debt\pom.xml clean test

# Ejecutar las cinco suites de Java:
foreach ($s in "gateway","ms-auth","ms-debt","ms-payments","ms-ai") { .\mvnw.cmd -q -f "$s\pom.xml" clean test }
```

| Modulo / Servicio | Cantidad de pruebas | Tipos de pruebas cubiertas | Herramientas / Frameworks |
| :--- | :---: | :--- | :--- |
| **ms-debt** | **532** | Reglas de negocio CAE, contratos, mandatos, campanas, outbox y reportes | JUnit 5, Mockito, AssertJ, MockMvc |
| **ms-auth** | **319** | Autenticacion passwordless, firma/validacion ES256, JWKS, rate limiting y auditoria | JUnit 5, Mockito, spring-security-oauth2-jose, MockMvc |
| **ms-payments** | **281** | Pasarelas (Webpay, Khipu, Mercado Pago), conciliacion, calculo UF y eventos RabbitMQ | JUnit 5, Mockito, AssertJ, MockMvc |
| **ms-ai** | **163** | Cliente LLM, motor local de fallback (`MotorLocal`), aislamiento de sesion y contexto | JUnit 5, Mockito, MockMvc |
| **gateway** | **25** | Enrutamiento de rutas, filtros de seguridad, cortafuegos de IPs y limites de tasa (Bucket4j) | JUnit 5, Spring Boot Test, MockMvc |
| **Total suite Java** | **1.320 pruebas** | Cobertura integral continua en cada Pull Request sin fallos | GitHub Actions CI (JDK 25) |

> **Nota sobre el portal:** El frontend no posee suite de pruebas unitarias automatizadas (`.test.jsx`); su verificacion se realiza en CI mediante compilacion estricta (`npm ci` y `npm run build`), control de tipos y revision manual en navegador. Para detalles adicionales sobre ejecucion y cobertura de pruebas, consultar [docs/pruebas/README.md](docs/pruebas/README.md).

---

## 7. Arquitectura de la solucion

### Descripcion de la arquitectura

DataBridge esta estructurado bajo una **arquitectura de microservicios orientada al dominio**, caracterizada por:

1. **Perimetro y Puerta de Entrada Unica**:
   El cliente web interactua exclusivamente con el servidor **nginx** del portal (en puerto `8080`), el cual delega las peticiones `/api` al **API Gateway** (**Spring Cloud Gateway**). El gateway centraliza las directivas CORS, aplica politicas de limitacion de tasa (*rate limiting*) mediante **Bucket4j** y enruta el trafico hacia los microservicios internos. Ningun servicio backend expone puertos directamente hacia redes no confiables.
2. **Microservicios de Backend Desacoplados (Java 25 / Spring Boot 3.5)**:
   - **`ms-auth`**: Autenticacion sin contrasenas, emision y rotacion de tokens JWT firmados con **ES256 (ECDSA P-256)**, publicacion del conjunto de claves publicas en `/.well-known/jwks.json` y despacho de correos transaccionales (Brevo/SMTP).
   - **`ms-debt`**: Gestion integral de la cartera: organizaciones, deudas, cargos, convenios de repactacion en cuotas con calculo de CAE, ejecucion legal de contactos en campanas, atencion de reclamos y generacion de certificados PDF.
   - **`ms-payments`**: Pasarela de pagos unificada (Webpay Plus, Khipu y Mercado Pago). Valida montos directamente con `ms-debt` (evitando manipulacion en el cliente), consulta el valor de la UF al **Banco Central de Chile** y realiza conciliacion idempotente servidor a servidor.
   - **`ms-ai`**: Asistente conversacional de solo lectura que consulta las deudas en `ms-debt` utilizando el token JWT del deudor autenticado, integrandose con modelos LLM externos (Groq/Gemini) o resolviendo con su motor de reglas local.
3. **Persistencia Aislada (*Database-per-service*)**:
   Cada microservicio dispone de su propia base de datos fisica en **MySQL 8.4** (`tb_auth`, `tb_debt`, `tb_payments`). No existen llaves foraneas entre bases distintas; las relaciones entre dominios se mantienen a nivel de aplicacion mediante identificadores de negocio (RUT, identificadores de deuda).
4. **Comunicacion y Mensajeria Asincrona**:
   El aviso de pago completado se propaga desde `ms-payments` hacia `ms-debt` mediante una cola dedicada en **RabbitMQ** (`ms-debt.pagos-confirmados`), garantizando persistencia y reintentos automaticos en caso de indisponibilidad temporal.

---

### Diagrama general de componentes y comunicacion

![Diagrama de Componentes](docs/diagramas/img/componentes.png)

---

### Diagrama de despliegue fisico

![Diagrama de Despliegue](docs/diagramas/img/despliegue.png)

---

### Diagrama de secuencia del flujo principal

![Diagrama de Secuencia](docs/diagramas/img/secuencia-pago.png)

```mermaid
sequenceDiagram
    actor D as Deudor
    participant P as Portal (React)
    participant Y as ms-payments
    participant M as ms-debt
    participant K as Pasarela (Khipu / Webpay)
    participant R as RabbitMQ
    participant B as Banco Central (UF)

    Note over D,P: 1. Autenticacion y seleccion de deuda
    D->>P: Ingresa RUT y codigo temporal
    P-->>D: Retorna sesion con JWT firmado en ES256
    D->>P: Selecciona deuda o cuotas y presiona "Pagar"

    Note over P,Y: 2. Apertura del cobro
    P->>Y: POST /api/payments/checkout
    alt Deuda expresada en UF
        Y->>B: Consulta UF oficial del dia (si3.bcentral.cl)
        B-->>Y: Retorna valor UF en pesos (CLP)
    end
    Y->>M: GET /internal/debts/{id} (con X-Internal-Key)
    M-->>Y: Monto oficial verificado (capital + mora - descuento)
    Y->>K: Inicia transaccion con la pasarela seleccionada
    K-->>Y: URL de redireccion y token de pago
    Y-->>P: Redirige al deudor a la pasarela

    Note over D,K: 3. Ejecucion del pago
    D->>K: Realiza el pago en el entorno seguro de la pasarela
    K-->>D: Retorna al portal tras finalizar

    Note over P,Y: 4. Verificacion y conciliacion servidor a servidor
    P->>Y: Solicita confirmacion del estado del pago
    Y->>K: Verifica transaccion de servidor a servidor
    K-->>Y: Estado aprobado y detalles de transaccion
    Y-->>P: "Pago aprobado" (comprobante generado)

    Note over Y,M: 5. Desacoplamiento asincrono
    Y->>R: Publica evento "pago.confirmado"
    R->>M: Entrega evento a ms-debt
    M->>M: Aplica abono en cuotas y salda deuda si saldo = 0
```

---

### Diagrama de casos de uso

![Diagrama de Casos de Uso](docs/diagramas/img/casos-de-uso.png)

---

### Diagramas entidad-relacion (ER)

#### Base de datos `tb_auth`
![ER tb_auth](docs/diagramas/img/entidad-relacion-tb-auth.png)

#### Base de datos `tb_debt`
![ER tb_debt](docs/diagramas/img/entidad-relacion-tb-debt.png)

#### Base de datos `tb_payments`
![ER tb_payments](docs/diagramas/img/entidad-relacion-tb-payments.png)

---

### Detalle de arquitectura en vistas 4+1

La documentacion tecnica complementaria y archivos fuente del modelo 4+1 se encuentran disponibles en:

| Vista 4+1 | Contenido y Especificacion | Documento y Ubicacion |
| :--- | :--- | :--- |
| **Escenarios (+1)** | Casos de uso de cada actor (Deudor, Empresa, Admin, Sistemas y Reloj) y detalle de endpoints. | [docs/diagramas/casos-de-uso.md](docs/diagramas/casos-de-uso.md)<br>[Diagrama](docs/diagramas/img/casos-de-uso.png) |
| **Vista Logica** | Diagramas Entidad-Relacion de las tres bases (`tb_auth`, `tb_debt`, `tb_payments`), clases y maquinas de estados. | [docs/diagramas/entidad-relacion.md](docs/diagramas/entidad-relacion.md)<br>[Diagramas ER](docs/diagramas/img/) |
| **Vista de Procesos** | Secuencias de interaccion y flujos de negocio BPMN. | [docs/diagramas/secuencia.md](docs/diagramas/secuencia.md)<br>[Diagrama de secuencia](docs/diagramas/img/secuencia-pago.png) |
| **Vista de Desarrollo** | Componentes por paquetes, HATEOAS, DTOs y frontend. | [docs/diagramas/componentes.md](docs/diagramas/componentes.md)<br>[Diagrama de componentes](docs/diagramas/img/componentes.png) |
| **Vista Fisica** | Topologia de despliegue Docker, orden de arranque y puertos. | [docs/diagramas/despliegue.md](docs/diagramas/despliegue.md)<br>[Diagrama de despliegue](docs/diagramas/img/despliegue.png) |

---

## Documentacion complementaria

Para profundizar en aspectos especificos del proyecto, consultar:

- **Reglas del negocio y modelo de comisiones:** [docs/negocio/reglas-negocio.md](docs/negocio/reglas-negocio.md)
- **Requisitos no funcionales (Seguridad, rendimiento y disponibilidad):** [docs/arquitectura/requisitos-no-funcionales.md](docs/arquitectura/requisitos-no-funcionales.md)
- **Configuracion y perfiles Docker:** [docs/despliegue/docker.md](docs/despliegue/docker.md)
- **Ejecucion y cobertura de pruebas:** [docs/pruebas/README.md](docs/pruebas/README.md)
- **Innovacion y propuesta de valor:** [docs/negocio/innovacion.md](docs/negocio/innovacion.md)
- **Recorrido guiado de demostracion:** [docs/demo/recorrido-demostracion.md](docs/demo/recorrido-demostracion.md)
- **Bitacora y estado del proyecto:** [docs/estado-proyecto.md](docs/estado-proyecto.md)
- **Politicas de cortafuegos y dominios:** [docs/firewalls-dominios.md](docs/firewalls-dominios.md)
