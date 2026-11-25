# Contacto del deudor: operación y validación (#471)

## Configuración

`CONTACT_VERIFICATION_KEY` debe ser una clave aleatoria independiente de al menos 32 caracteres,
igual en todas las instancias de ms-debt y externa a la base. Vacía deshabilita las solicitudes
con 503; nunca degrada a hash rápido ni texto plano. Rotarla invalida desafíos pendientes.
`CIFRADO_LLAVE` cifra correos de desafíos y avisos con el mecanismo existente AES-GCM.
Al rotarla, conservar la anterior hasta agotar desafíos y avisos pendientes: la herramienta
existente de recifrado solo migra suscripciones. No retirar una clave con avisos por leer.

`CONTACT_TRUSTED_PROXIES` es una expresión regular que identifica únicamente los pares de
confianza de la ruta gateway/proxy. Sin configurar, las cabeceras reenviadas se ignoran y se
limita al par directo: detrás del gateway el límite por origen será conservador y compartido.
Para distinguir clientes, configurar los saltos reales de la instalación y asegurar que el
proxy de entrada sobrescriba X-Forwarded-For. No confiar en redes arbitrarias.

Migraciones aditivas: ms-debt V17 y ms-auth V12 (después de la V16 de ms-debt y las V10 y V11 de ms-auth de #486 y #505). Desplegar primero ms-debt con el endpoint interno
de contacto y luego ms-auth. Nunca publicar `/internal` en el gateway. El código nuevo de
ms-auth falla cerrado si ms-debt aún no soporta la comprobación; coordinar la ventana operativa.

## Garantías y límites

Los JWT actuales no tienen vínculo con una familia de sesión ni `auth_time` original en
ms-debt: un JWT renovado no prueba autenticación reciente. Esta implementación exige sesión
válida (TTL habitual 15 minutos), sin afirmar reautenticación reciente ni revocación instantánea.
Una sesión robada puede verificar un buzón del atacante. La mitigación incluye aviso previo,
expiración del JWT y rechazo de futuras renovaciones anteriores al cambio. Soporte es necesario
para quien ya perdió el correo y la sesión; el formulario no recupera cuentas sin autenticación.

La ficha se bloquea para teléfono, desafíos, confirmaciones y carteras; la versión optimista
impide que escrituras previas de otras rutas reviertan un cambio. Intentos fallidos se confirman
con `noRollbackFor=ApiException`; fallos de persistencia revierten la transacción completa.
Los desafíos sobreviven a reinicios; intentos y límites residen en MySQL, no en la memoria del proceso.
Los desafíos vencidos se purgan después de 24 horas; contadores antiguos, después de un día.
La auditoría no guarda valores de contacto. La nueva dirección permanece cifrada en el desafío
hasta su purga. El aviso antiguo queda cifrado hasta entrega; después se borra ese valor. Al
confirmar el correo, el navegador de esa sesión recibe una sesión nueva y se cierran todas las
otras sesiones del deudor. La regla exige que el cambio sea de los últimos diez minutos y posterior
al inicio de la familia de sesión usada.

El aviso al correo anterior incluye un código aleatorio de ocho caracteres, válido por siete días,
para deshacer un cambio que el deudor no pidió. El código en claro se borra al entregar el aviso y
en la base queda solo su HMAC. El mensaje no incluye RUT, deuda, acreedor, monto ni la dirección
nueva. El formulario público pide RUT y código, sin JWT. Un código inválido responde el mismo
mensaje exista o no el RUT; cada fallo suma un intento a los códigos vigentes y al quinto dejan de
servir. Se limitan las solicitudes a 10 por origen y a 5 por RUT cada 15 minutos. Al acertar, se
restaura el correo anterior, se cancelan los otros códigos y verificaciones pendientes, se registra
la reversión y se cierran las sesiones. Si ms-auth falla, el correo igual se revierte y la siguiente
renovación de una sesión antigua queda rechazada por el cambio de contacto.

La bandeja privada de avisos se escribe con el cambio; no se adapta la bandeja de webhooks,
porque exige una suscripción de empresa. Se reintenta hasta siete veces. En ms-auth se reclama
el evento antes de invocar el transporte. Si una respuesta se pierde tras aceptación, un evento
SENT se reconoce sin duplicar. Si el proceso cae o el transporte devuelve aceptación ambigua,
el recibo SENDING exige revisión operativa: no existe exactamente-una-vez general con SMTP.
No borrar/rearmar ese recibo sin comprobar previamente la entrega en el proveedor. Una falta de
aviso no revierte un correo confirmado. No hay broker adicional ni transacción JPA distribuida.

## Recorrido integrado pendiente de infraestructura

Usar MySQL desechable, claves efímeras y un proveedor HTTP/SMTP controlado con destinatarios
sintéticos. Mailpit se rechaza para acreditar propiedad, incluso en desarrollo.

1. Autenticar un deudor y emitir antes un OTP y enlace al correo previo; guardar sus identificadores.
2. Solicitar cambio desde Mis datos. Consultar la ficha: conserva el correo anterior. Capturar el
   código solo en el proveedor de pruebas; comprobar que JSON y logs públicos no lo contienen.
3. Probar código erróneo y luego correcto. Comprobar una auditoría EMAIL, fecha, rechazo reiniciado,
   desafío CONSUMED y un aviso privado. Repetir confirmación: 409; petición ajena: rechazada.
4. Inspeccionar el aviso anterior: trae el código de reversión, pero no RUT, deuda, acreedor, monto
   ni dirección nueva.
5. Confirmar el cambio: comprobar que este navegador obtiene una familia nueva y las otras sesiones
   no se pueden renovar. La sesión usada debe haber empezado antes del cambio; probar también un
   cambio de más de diez minutos y uno anterior al inicio de la sesión (409).
6. Bloquear la entrega del aviso y comprobar que el cambio de correo no se deshace por ese fallo.
   Con un aviso controlado, probar código errado, vencimiento, cinco fallos y RUT inexistente; los
   errores deben mantener el mismo mensaje genérico. Probar también los límites por origen y RUT.
7. Usar un código correcto sin sesión: debe volver el correo anterior, invalidar verificaciones y
   otros códigos, escribir CONTACT_REVERTED y cerrar las sesiones. Si ms-auth falla, comprobar que
   la reversión igualmente queda aplicada.
8. Pedir nuevo acceso: solo al correo vigente. Enviar un recordatorio y comprobar mismo destino.
9. Cargar carteras de dos acreedores con valores anteriores: ambos contactos corregidos prevalecen,
   nombre/deudas se actualizan, una sola ficha por RUT e idempotencia de lote conservada.
10. Confirmar simultáneamente desde dos conexiones MySQL: una sola actualización/auditoría/aviso.
11. Reiniciar ms-debt entre solicitud y confirmación; el desafío sigue disponible y mantiene intentos.
12. Recorrer Edge a 1360 y 390 px, temas claro/oscuro, teclado, errores, cancelación y carga.

Las pruebas `ContactoMySqlTest` usan Testcontainers mysql:8.4. Fuera de CI, sin Docker se omiten;
en CI su ausencia es un fallo de infraestructura. No contar una prueba omitida como aprobada.
