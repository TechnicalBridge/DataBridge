# Validación de contrato de acceso de APOFYX

## Decisión

La implementación canónica por código ya existe en el commit `79e272f` (PR #223) de TechnicalBridge/TB_web. Esta rama se basa en ese commit para validar conjuntamente frontend y ms-auth. No reinventa autenticación ni modifica usuarios, roles o la base desplegada.

El portal solicita `POST /api/auth/codigo` con `{correo, rut: null}` para personal y verifica mediante `POST /api/auth/acceso` con `{correo, codigo}`. Los deudores conservan `{rut, codigo}`. `/api/auth/enlace` ofrece el respaldo combinado y `/api/auth/verify` canjea el token. Código y enlace comparten consumo, caducidad y límites. El personal recibe sesión CREDITOR o ADMIN según su rol real, no según el formulario.

La implementación incluye la migración V6; ejecutarla en production sería un cambio de base y requiere revisión/autorización separada. No se ha aplicado desde este diagnóstico.

## Evidencia del incidente

El 19/10/2026 a las 05:37:47 de Santiago, una única petición de acceso para Camila a `/api/auth/codigo` recibió 403 sin cuerpo. Una comprobación OPTIONS al Gateway con el origen público también recibió 403. CORS_ORIGINS estaba ausente. El ms-auth activo ejecutaba `520ec8d`, anterior al contrato por código para personal; una petición con cuerpo vacío al endpoint interno respondió que la ruta no existe. Esta comprobación no emitió otro código.

Son dos causas independientes: origen público no permitido y frontend/backend desalineados. Configurar CORS no crea el endpoint que falta.

## Alcance de esta rama

- Reutilizar la implementación y pruebas de #223.
- Agregar una regresión de enrutamiento del contrato público y mantener inaccesible la emisión interna.
- No cambiar SecurityConfig, modelos de roles, Camila, APOFYX, Marcela ni ADMIN_CORREO.
- No desplegar esta rama ni modificar main.

## Revisión antes de autorizar despliegue

Revisar AuthController, AuthService, MagicLink, repositorios, plantillas, DTO, frontend y V6 que ya forman parte de #223. Probar ms-auth, gateway, aislamiento de organizaciones en ms-debt y compilación del frontend. El resultado local no acredita entrega real de Brevo ni una sesión E2E en Railway.

El único cambio productivo autorizado es CORS_ORIGINS del Gateway con el origen público exacto, sin slash final. Las demás variables permanecen intactas. No se debe cambiar la rama seguida por production ni ejecutar V6 sin autorización explícita posterior.

## Resultado de la validación en esta rama

- ms-auth: 222 pruebas, cero fallos, errores o pruebas omitidas.
- ms-debt: 412 pruebas, cero fallos, errores o pruebas omitidas; incluye restricciones de empresa y administración global.
- gateway: 22 pruebas, cero fallos, errores o pruebas omitidas.
- Frontend: `npm run build`, 764 módulos. Bundle principal `index-BlAVu_Un.js`, igual al nombre observado en el portal público durante el diagnóstico.
- Regresión HTTP local: preflight permitido 200, POST de solicitud reenviado 202 y payload conservado; origen ajeno 403. Auth es un doble HTTP local en esta prueba, no un despliegue productivo ni un proveedor de correo real.
- Railway Gateway tras aplicar solo CORS_ORIGINS: deployment exitoso, health UP/200 y preflight público 200 con Access-Control-Allow-Origin exacto. Antes devolvía 403.
- No se solicitó otro código real ni se verificó sesión, entrega de correo o cartera con identidad real después de estos cambios.

No hay nueva implementación de autenticación que integrar: #223 ya incorporó el contrato en origin/main. Esta rama aísla ese estado para revisión, excluyendo el cambio posterior de datos de demostración #235. Los únicos archivos nuevos o modificados respecto de 79e272f son este informe y las regresiones de Gateway. La corrección pendiente en production es alinear el despliegue de Auth con el contrato aprobado, previa autorización expresa.
