# Content Security Policy del portal

La política del SPA se configura en `frontend/nginx.conf.template`; Caddy, en
ambas variantes, conserva la cabecera de nginx sin duplicarla.

| Directiva | Motivo |
| --- | --- |
| default-src 'self' | Recursos locales por omisión |
| script-src 'self' | Bundles locales y tema inicial síncrono, sin eval ni scripts inline |
| style-src 'self' 'unsafe-inline' fonts.googleapis.com | CSS local, Google Fonts y estilos dinámicos de React/Recharts |
| font-src 'self' fonts.gstatic.com | Fuentes locales y Source Sans 3 de Google |
| img-src 'self' data: | Marca y recursos locales, imágenes embebidas |
| connect-src 'self' | API por el gateway del mismo origen |
| object-src 'none' | No se requieren plugins embebidos |
| base-uri 'none' | No se usa elemento base |
| frame-ancestors 'none', frame-src 'none' | El portal no se embebe ni carga frames |
| form-action 'self' | Formularios del SPA; pagos navegan a checkoutUrl |

La excepción de estilos inline no habilita scripts inline. `tema-inicial.js`
se carga antes del contenido, conservando el comportamiento del tema sin hashes
dependientes del build. Las descargas CSV y PDF utilizan URLs blob de descarga;
no son conexiones de fetch ni objetos embebidos.

Las rutas `/api/`, `/v3/api-docs`, `/swagger-ui` y `/webjars/` no reciben la CSP
del SPA. En particular, el HTML de `/api/payments/public/{id}/webpay` conserva
el formulario y su autoenvío actuales: este cambio no añade una CSP específica
al backend. Esa superficie queda pendiente de endurecimiento separado, sin
modificar tokens, adaptadores ni confirmaciones de pago.

Los assets repiten las cabeceras de seguridad porque su `add_header` de caché
impide heredar las del servidor. Se conserva caché inmutable. La CSP usa
`always` también en respuestas de error del portal.

Validación pendiente antes de considerar terminado #425: nginx real y cabeceras
HTTP 200/errores/assets; ambas variantes de Caddy; Edge 1360 y 390 en ambos temas;
consola sin errores CSP; acceso, empresa, panel y tres pasarelas de integración.
No se ejecutan cobros reales ni se despliega esta configuración desde este PR.
