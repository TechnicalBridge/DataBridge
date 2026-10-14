# Prueba de punta a punta con buzón real (Gmail y Outlook)

Esta prueba comprueba de extremo a extremo que:
1. DataBridge emite un código de acceso para un deudor por la API interna (`POST /internal/codigos`).
2. El servidor SMTP entrega el correo al buzón real correspondiente (Gmail con alias `+`, y Outlook).
3. El script conecta por IMAP (puerto 993 con SSL), extrae el código del cuerpo del correo y mide cuánto tardó en llegar (falla si tarda más de 2 minutos / 120 s).
4. El script valida el código recibido contra el endpoint de inicio de sesión (`POST /api/auth/acceso`) y comprueba que se genera el token de sesión.

---

## Cuentas de prueba

| Cuenta | Proveedor | Para qué | Variable / Secreto |
| --- | --- | --- | --- |
| **Remitente** | Gmail | DataBridge envía desde ella por SMTP | `SMTP_USER`, `SMTP_PASSWORD` |
| **Buzón de deudores** | Gmail | Recibe correos con alias `+` (`...+deudorX@gmail.com`) | `GMAIL_USER`, `GMAIL_APP_PASSWORD` |
| **Buzón externo** | Outlook | Comprueba entrega a otro proveedor (`...@outlook.com`) | `OUTLOOK_USER`, `OUTLOOK_APP_PASSWORD` |

> [!IMPORTANT]
> **Ninguna credencial se escribe en el repositorio.** Salen de variables de entorno o de los *secrets* de GitHub Actions.

---

## Cómo correr la prueba a mano

1. Asegúrate de que los servicios estén arriba y configurados para enviar correo real:
   ```powershell
   # En tu .env local:
   # SMTP_HOST=smtp.gmail.com
   # SMTP_PORT=587
   # SMTP_USER=databridge.pruebas@gmail.com
   # SMTP_PASSWORD=<contraseña-de-aplicación>
   # SMTP_AUTH=true
   # SMTP_STARTTLS=true
   # MAIL_FROM=databridge.pruebas@gmail.com
   ```

2. Exporta las variables del buzón y la clave interna:
   ```powershell
   $env:AUTH_URL = "http://localhost:8081"
   $env:INTERNAL_KEY = "<clave-interna>"
   $env:GMAIL_USER = "deudores.databridge@gmail.com"
   $env:GMAIL_PASSWORD = "<contraseña-de-aplicacion-imap>"
   $env:OUTLOOK_USER = "deudor.databridge@outlook.com"
   $env:OUTLOOK_PASSWORD = "<contraseña-de-aplicacion-imap>"
   ```

3. Ejecuta el script:
   ```powershell
   python pruebas/correo-real/probar_correo_real.py
   ```

---

## Ejecución desde GitHub Actions

El workflow `.github/workflows/correo-real.yml` permite disparar la prueba de forma manual:
- Ir a la pestaña **Actions** en GitHub.
- Seleccionar **Prueba E2E de correo real**.
- Hacer clic en **Run workflow** (`workflow_dispatch`).
- Utiliza los secretos configurados en el repositorio:
  - `INTERNAL_KEY`
  - `GMAIL_USER`
  - `GMAIL_APP_PASSWORD`
  - `OUTLOOK_USER`
  - `OUTLOOK_APP_PASSWORD`
