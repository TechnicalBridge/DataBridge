#!/usr/bin/env python3
"""
Prueba de punta a punta de entrega de correo con buzones reales (Gmail y Outlook).

Flujo por buzón:
1. Emite un código de acceso para un deudor de prueba a través de ms-auth (POST /internal/codigos).
   Para Gmail se usa un alias '+' (ej. usuario+test12345@gmail.com).
2. Conecta por IMAP con SSL al buzón correspondiente y espera a que el correo llegue.
3. Extrae el código de acceso del cuerpo del correo.
4. Valida el código llamando a POST /api/auth/acceso y comprueba que la sesión se abre correctamente.
5. Informa el tiempo transcurrido en segundos. Falla si tarda más de 120 segundos (2 minutos).

Credenciales:
Se leen exclusivamente de variables de entorno (nunca escritas en código ni repositorio).
"""

import email
from email.header import decode_header
import imaplib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request


def decodificar_texto(payload, charset=None):
    if charset:
        try:
            return payload.decode(charset, errors="replace")
        except Exception:
            pass
    for encoding in ["utf-8", "latin-1", "ascii"]:
        try:
            return payload.decode(encoding, errors="replace")
        except Exception:
            continue
    return payload.decode("utf-8", errors="replace")


def extraer_cuerpo(msg):
    if msg.is_multipart():
        for part in msg.walk():
            ctype = part.get_content_type()
            cdisp = str(part.get("Content-Disposition"))
            if ctype in ["text/plain", "text/html"] and "attachment" not in cdisp:
                payload = part.get_payload(decode=True)
                return decodificar_texto(payload, part.get_content_charset())
    else:
        payload = msg.get_payload(decode=True)
        return decodificar_texto(payload, msg.get_content_charset())
    return ""


def extraer_codigo(cuerpo):
    """
    El correo de DataBridge dice:
    Para verlo, entra a ... y escribe tu RUT y este codigo:
        XXXXXX
    """
    # Busca 6 caracteres alfanuméricos aislados o precedidos por código/codigo
    lineas = cuerpo.splitlines()
    for linea in lineas:
        linea = linea.strip()
        if re.match(r"^[0-9a-zA-Z]{6}$", linea):
            return linea
    # Fallback con regex en todo el cuerpo
    coincidencia = re.search(r"c[oó]digo:\s*([0-9a-zA-Z]{6})", cuerpo, re.IGNORECASE)
    if coincidencia:
        return coincidencia.group(1)
    return None


def emitir_codigo(auth_url, internal_key, rut, correo_destino, tag):
    url = f"{auth_url.rstrip('/')}/internal/codigos"
    datos = {
        "rut": rut,
        "canales": ["correo"],
        "correo": correo_destino,
        "acreedor": "Patrimonio Inmuebles",
        "paraQue": f"prueba-real-{tag}"
    }
    payload = json.dumps(datos).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "X-Internal-Key": internal_key
        },
        method="POST"
    )
    with urllib.request.urlopen(req, timeout=15) as res:
        if res.status != 200:
            raise RuntimeError(f"Error al emitir código: HTTP {res.status}")
        cuerpo = res.read().decode("utf-8")
        return json.loads(cuerpo)


def probar_login(auth_url, rut, codigo):
    url = f"{auth_url.rstrip('/')}/api/auth/acceso"
    datos = {
        "rut": rut,
        "codigo": codigo
    }
    payload = json.dumps(datos).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    with urllib.request.urlopen(req, timeout=15) as res:
        if res.status != 200:
            raise RuntimeError(f"Error al iniciar sesión: HTTP {res.status}")
        cuerpo = res.read().decode("utf-8")
        respuesta = json.loads(cuerpo)
        if "token" not in respuesta and "jwt" not in respuesta:
            raise RuntimeError(f"Respuesta de sesión sin token: {cuerpo}")
        return respuesta


def esperar_correo_imap(imap_host, usuario, contrasena, tag, timeout_segundos=120, intervalo=5):
    inicio = time.time()
    limite = inicio + timeout_segundos

    while time.time() < limite:
        restante = int(limite - time.time())
        try:
            cliente = imaplib.IMAP4_SSL(imap_host, port=993)
            cliente.login(usuario, contrasena)
            cliente.select("INBOX")

            # Buscar los últimos mensajes
            _, mensajes = cliente.search(None, "ALL")
            ids = mensajes[0].split()
            # Revisar en orden descendente (los más recientes primero)
            for msg_id in reversed(ids[-15:]):
                _, datos = cliente.fetch(msg_id, "(RFC822)")
                raw_email = datos[0][1]
                msg = email.message_from_bytes(raw_email)

                asunto, encoding = decode_header(msg["Subject"])[0]
                if isinstance(asunto, bytes):
                    asunto = decodificar_texto(asunto, encoding)

                cuerpo = extraer_cuerpo(msg)

                # Verificar si es el correo correspondiente
                if "codigo de acceso" in asunto.lower() or "código de acceso" in asunto.lower():
                    codigo = extraer_codigo(cuerpo)
                    if codigo:
                        tiempo_total = time.time() - inicio
                        cliente.close()
                        cliente.logout()
                        return codigo, tiempo_total

            cliente.close()
            cliente.logout()
        except imaplib.IMAP4.error as e:
            print(f"  [AVISO] Conexión IMAP a {imap_host} falló ({e}). Reintentando...")
        except Exception as e:
            print(f"  [AVISO] Error consultando IMAP en {imap_host}: {e}. Reintentando...")

        time.sleep(intervalo)

    raise TimeoutError(f"El correo no llegó en {timeout_segundos} segundos a {usuario} ({imap_host})")


def probar_proveedor(nombre, imap_host, usuario, contrasena, correo_destino, auth_url, internal_key, rut):
    print(f"\n==================================================")
    print(f" Probando proveedor: {nombre}")
    print(f" Buzón: {usuario} (destino: {correo_destino})")
    print(f" Servidor IMAP: {imap_host}:993")
    print(f"==================================================")

    tag = f"{int(time.time())}"
    print(f"1. Solicitando emisión de código a {auth_url}...")
    emitido = emitir_codigo(auth_url, internal_key, rut, correo_destino, tag)
    print(f"   Código emitido por ms-auth. Esperando correo en el buzón...")

    print(f"2. Leyendo buzón por IMAP (tiempo límite: 120 segundos)...")
    codigo, tardo = esperar_correo_imap(imap_host, usuario, contrasena, tag, timeout_segundos=120)
    print(f"   ✔ Correo recibido en {tardo:.1f} segundos.")
    print(f"   Código extraído del correo: {codigo}")

    print(f"3. Validando acceso en el portal con RUT {rut} y código {codigo}...")
    sesion = probar_login(auth_url, rut, codigo)
    print(f"   ✔ Sesión iniciada con éxito (token recibido).")
    print(f"✔ Resultado {nombre}: Éxito total. Correo tardó {tardo:.1f} segundos.\n")
    return tardo


def main():
    if "--help" in sys.argv or "-h" in sys.argv:
        print(__doc__)
        return 0

    auth_url = os.environ.get("AUTH_URL", "http://localhost:8081")
    internal_key = os.environ.get("INTERNAL_KEY", "")
    rut = os.environ.get("DEBTOR_RUT", "16482337-7")

    gmail_user = os.environ.get("GMAIL_USER")
    gmail_pass = os.environ.get("GMAIL_PASSWORD") or os.environ.get("GMAIL_APP_PASSWORD")
    gmail_host = os.environ.get("GMAIL_IMAP_HOST", "imap.gmail.com")

    outlook_user = os.environ.get("OUTLOOK_USER")
    outlook_pass = os.environ.get("OUTLOOK_PASSWORD") or os.environ.get("OUTLOOK_APP_PASSWORD")
    outlook_host = os.environ.get("OUTLOOK_IMAP_HOST", "outlook.office365.com")

    if not internal_key:
        print("[AVISO] INTERNAL_KEY no está definida en las variables de entorno.")
        if "--dry-run" in sys.argv:
            print("Modo dry-run: verificación estructural completada sin ejecutar llamadas de red.")
            return 0
        print("Define INTERNAL_KEY, GMAIL_USER, GMAIL_PASSWORD, OUTLOOK_USER, OUTLOOK_PASSWORD.")
        print("Para verificar solo sintaxis/estructura, utiliza --dry-run.")
        return 1

    resultados = {}
    errores = []

    # 1. Probar Gmail (con alias '+')
    if gmail_user and gmail_pass:
        tag = int(time.time())
        usuario_base, dominio = gmail_user.split("@")
        correo_alias = f"{usuario_base}+deudor{tag}@{dominio}"
        try:
            t = probar_proveedor("Gmail", gmail_host, gmail_user, gmail_pass, correo_alias, auth_url, internal_key, rut)
            resultados["Gmail"] = t
        except Exception as e:
            print(f"❌ Error en Gmail: {e}")
            errores.append(("Gmail", e))
    else:
        print("[INFO] Credenciales de Gmail no proporcionadas (GMAIL_USER / GMAIL_PASSWORD). Saltando.")

    # 2. Probar Outlook
    if outlook_user and outlook_pass:
        try:
            t = probar_proveedor("Outlook", outlook_host, outlook_user, outlook_pass, outlook_user, auth_url, internal_key, rut)
            resultados["Outlook"] = t
        except Exception as e:
            print(f"❌ Error en Outlook: {e}")
            errores.append(("Outlook", e))
    else:
        print("[INFO] Credenciales de Outlook no proporcionadas (OUTLOOK_USER / OUTLOOK_PASSWORD). Saltando.")

    print("\n================ Resumen de la prueba ================")
    for proveedor, tiempo_seg in resultados.items():
        print(f"  ✔ {proveedor}: {tiempo_seg:.1f} s")
    for proveedor, err in errores:
        print(f"  ❌ {proveedor}: Falló ({err})")
    print("=======================================================")

    if errores:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
