# La documentación de DataBridge

Todo lo que explica DataBridge está aquí, en archivos `.md`, un tema por carpeta. El [README de la raíz](../README.md)
es la entrada: qué hace DataBridge, cómo se levanta y su arquitectura. Desde ahí se llega a cada tema.

| Carpeta | Qué trae | Para quién |
| --- | --- | --- |
| [`diagramas`](diagramas/README.md) | La arquitectura en el modelo 4+1: casos de uso, clases, entidad-relación, estados, secuencias, procesos, componentes y despliegue, con los diagramas del equipo | Todos |
| [`base-de-datos`](base-de-datos/README.md) | Las tres bases, una por servicio, y sus migraciones con Flyway; por qué no se cruzan, y cómo pasar los datos del MySQL único de antes | Quien programa u opera |
| [`integracion`](integracion/README.md) | El contrato v1 con que el sistema de una empresa entrega su cartera y recibe los avisos. La API que lo atiende está apagada por omisión | Quien conecta otro sistema |
| [`eventos`](eventos/README.md) | El aviso de pago que ms-payments le manda a ms-debt | Quien programa ms-debt o ms-payments |
| [`seguridad`](seguridad/jwt.md) | [Las llaves de las sesiones](seguridad/jwt.md), cómo se generan y cómo se cambian; y [que ninguna clave llegue al repositorio](seguridad/secretos.md) | Quien opera o programa |
| [`despliegue`](despliegue/README.md) | DataBridge en el servidor de la demo, paso a paso | Quien despliega |
| [`cartera`](cartera/README.md) | Cómo probar la carga de la cartera, y la demo que viene incluida | Quien prueba |
| [`rendimiento`](rendimiento/README.md) | Las pruebas de carga con k6 y lo que encontraron. Los scripts están en [`rendimiento/`](../rendimiento/) | Quien opera |
| [`metodologia`](metodologia/plan-kanban.md) | El seguimiento del plan de trabajo Kanban, y en qué se apartó el proyecto | El equipo y quien evalúa |

## Cómo se escribe aquí

- **Solo archivos `.md`**, y las imágenes que usan los diagramas (`diagramas/img/`). Los datos de prueba no van
  aquí: viven en `src/test/resources` de cada servicio.
- **Un tema por carpeta.** Si un cambio toca un tema, su archivo se actualiza en el mismo PR, y este índice
  también si aparece un tema nuevo.
- **Cada diagrama Mermaid se valida dibujándolo con Mermaid 11**, y cada enlace relativo tiene que llegar a un
  archivo que existe.
