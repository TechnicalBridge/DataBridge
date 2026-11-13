# La documentación de DataBridge

Todo lo que explica DataBridge está aquí, en archivos `.md`, un tema por carpeta. El [README de la raíz](../README.md)
es la entrada: qué hace DataBridge, cómo se levanta y su arquitectura. Desde ahí se llega a cada tema.

| Carpeta | Qué trae | Para quién |
| :--- | :--- | :--- |
| [`diagramas`](diagramas/README.md) | La arquitectura en el modelo 4+1: casos de uso, clases, entidad-relación, estados, secuencias, procesos, componentes y despliegue, con los diagramas del equipo | Todos |
| [`arquitectura`](arquitectura/requisitos-no-funcionales.md) | Requisitos no funcionales: seguridad, cifrado, rendimiento, disponibilidad y resiliencia | Quien diseña u opera |
| [`negocio`](negocio/reglas-negocio.md) | Reglas del negocio, cálculo de comisiones, CAE, límites de cobranza y propuesta de [innovación](negocio/innovacion.md) | Quien define el negocio |
| [`base-de-datos`](base-de-datos/README.md) | Las tres bases, una por servicio, y sus migraciones con Flyway; por qué no se cruzan, y cómo pasar los datos del MySQL único de antes | Quien programa u opera |
| [`integracion`](integracion/README.md) | El contrato v1 con que el sistema de una empresa entrega su cartera y recibe los avisos. La API que lo atiende está apagada por omisión | Quien conecta otro sistema |
| [`eventos`](eventos/README.md) | El aviso de pago que ms-payments le manda a ms-debt por RabbitMQ | Quien programa ms-debt o ms-payments |
| [`seguridad`](seguridad/jwt.md) | [Las llaves de las sesiones](seguridad/jwt.md), cómo se generan y cómo se cambian; y [que ninguna clave llegue al repositorio](seguridad/secretos.md) | Quien opera o programa |
| [`despliegue`](despliegue/docker.md) | Configuración de [Docker y Docker Compose](despliegue/docker.md), y DataBridge en el [servidor de la demo](despliegue/README.md), paso a paso | Quien despliega |
| [`pruebas`](pruebas/README.md) | Las 1.359 pruebas automatizadas en Java, ejecución con JDK 25, reportes Surefire y flujos de CI | Quien prueba o programa |
| [`cartera`](cartera/README.md) | Cómo probar la carga de la cartera, y la demo que viene incluida | Quien prueba |
| [`demo`](demo/recorrido-demostracion.md) | Recorrido guiado de demostración con datos de prueba para deudores y empresas | Quien evalúa o demuestra |
| [`rendimiento`](rendimiento/README.md) | Las pruebas de carga con k6 y lo que encontraron. Los scripts están en [`performance-tests/`](../performance-tests/) | Quien opera |
| [`metodologia`](metodologia/plan-kanban.md) | El [plan de trabajo Kanban](metodologia/plan-kanban.md), en qué se apartó el proyecto y el [estado de avance del proyecto](metodologia/estado-proyecto.md) | El equipo y quien evalúa |

## Cómo se escribe aquí

- **Solo archivos `.md`**, y las imágenes que usan los diagramas (`diagramas/img/`). Los datos de prueba no van
  aquí: viven en `src/test/resources` de cada servicio.
- **Un tema por carpeta.** Si un cambio toca un tema, su archivo se actualiza en el mismo PR, y este índice
  también si aparece un tema nuevo.
- **Cada diagrama Mermaid se valida dibujándolo con Mermaid 11**, y cada enlace relativo tiene que llegar a un
  archivo que existe.
