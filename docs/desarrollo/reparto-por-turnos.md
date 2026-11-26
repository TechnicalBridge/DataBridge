# Reparto por turnos

El workflow serializa las ejecuciones y cada una reconcilia todos los issues
abiertos pendientes, con paginación completa. La ejecución programada recupera
fallos y eventos cancelados; depende de que GitHub Actions vuelva a estar disponible.
No se garantiza un plazo máximo de recuperación.

El orden usa creación y número como desempate. Una asignación individual del
equipo fija el siguiente turno. Se conservan integrantes, orden, exclusiones y
asignaciones existentes. También se pueden recuperar issues antiguos manualmente.
Las tareas cerradas no se asignan; conservan su posición histórica en el turno.

Antes de asignar se vuelve a consultar el issue. GitHub no ofrece una operación
condicional de asignación: un cambio manual entre esa lectura y el POST podría
añadir un segundo responsable. No se eliminan responsables ni se sobrescriben
asignaciones; ese caso requiere revisión humana. Los fallos de API abortan la
pasada y se recuperan en la siguiente, conservando lo ya asignado.

Validación local: `node --test pruebas/repartir-por-turnos.test.cjs`.
Las pruebas usan datos sintéticos, sin crear issues reales.
