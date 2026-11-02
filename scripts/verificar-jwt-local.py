"""E2E JWT contra Docker LOCAL, con proyecto/volumen/puertos nuevos.

Requiere Python + cryptography y las seis imagenes issue82 construidas.
No lee el .env real ni imprime tokens, cookies, codigos o claves.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def keypair(directory: Path):
    directory.mkdir(mode=0o700)
    private = ec.generate_private_key(ec.SECP256R1())
    for name, data in (
        ("private.pem", private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())),
        ("public.pem", private.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)),
    ):
        fd = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
    return private


def forged(private, claims, kid=None, alg="ES256"):
    header = {"alg": alg}
    if kid is not None:
        header["kid"] = kid
    message = (b64(json.dumps(header).encode()) + "." + b64(json.dumps(claims).encode())).encode()
    if alg == "HS256":
        signature = hmac.new(b"local-fictional-attack-key-not-used-by-services", message, hashlib.sha256).digest()
    else:
        der = private.sign(message, ec.ECDSA(hashes.SHA256()))
        r, s = decode_dss_signature(der)
        signature = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return message.decode() + "." + b64(signature)


_ports = set()


def port():
    while True:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            candidate = sock.getsockname()[1]
        if candidate not in _ports:
            _ports.add(candidate)
            return candidate


def http(base, path, *, method="GET", body=None, token=None, headers=None, opener=None):
    request_headers = dict(headers or {})
    if token:
        request_headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base + path, data=data, method=method, headers=request_headers)
    client = opener or urllib.request.build_opener(NoRedirect())
    try:
        response = client.open(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = response.read()
        try:
            parsed = json.loads(payload) if payload else None
        except (ValueError, UnicodeDecodeError):
            parsed = None
        return response.code, parsed


def expect(actual, wanted, label):
    if actual != wanted:
        raise RuntimeError(f"{label}: HTTP esperado {wanted}, obtenido {actual}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image-tag", required=True, help="issue82-<sha> ya construido, sin registry remoto")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"issue82-[a-f0-9]{7,40}", args.image_tag):
        raise SystemExit("Etiqueta local de pruebas invalida")
    root = Path(__file__).resolve().parents[1]
    project = "tbridge-jwt-e2e-" + uuid.uuid4().hex[:12]
    scratch = Path(tempfile.mkdtemp(prefix=project + "-"))
    summary = {"project": project, "image_tag": args.image_tag, "checks": {}, "cleanup": "NOT_STARTED"}
    report = args.report or root.parent / "issue82-e2e-results.json"
    app_keys = [line.split("=", 1)[0] for line in (root / ".env.example").read_text(encoding="utf-8").splitlines()
                if re.match(r"^[A-Z_][A-Z_0-9]*=", line)]
    environment = os.environ.copy()
    for name in app_keys:
        environment.pop(name, None)
    # No heredar un archivo/proyecto Compose alternativo ni conectar a un daemon remoto.
    for name in ("COMPOSE_FILE", "COMPOSE_PROJECT_NAME", "COMPOSE_PROFILES", "COMPOSE_ENV_FILES"):
        environment.pop(name, None)
    if environment.get("DOCKER_HOST") and not environment["DOCKER_HOST"].startswith(("unix:", "npipe:")):
        raise SystemExit("Solo se permite Docker local")
    context = subprocess.run(["docker", "context", "show"], capture_output=True, text=True, env=environment, check=True).stdout.strip()
    endpoint = subprocess.run(["docker", "context", "inspect", context, "--format", "{{.Endpoints.docker.Host}}"],
                              capture_output=True, text=True, env=environment, check=True).stdout.strip()
    if not endpoint.startswith(("unix:", "npipe:")):
        raise SystemExit("El contexto Docker no es local")
    docker = ["docker", "--context", context]

    def command(arguments, *, input_text=None):
        result = subprocess.run(docker + arguments, cwd=root, env=environment, input=input_text,
                                capture_output=True, text=True, encoding="utf-8", errors="replace")
        if result.returncode:
            # No volcar stdout/stderr de procesos que podrian contener datos de sesion.
            raise RuntimeError("Fallo comando Docker aislado: " + " ".join(arguments[:2]))
        return result.stdout

    def passed(label):
        summary["checks"][label] = "PASS"
        print(label + ": PASS", flush=True)

    ports = {name: port() for name in ("auth", "debt", "payments", "portal", "mailpit")}
    urls = {name: f"http://127.0.0.1:{value}" for name, value in ports.items()}
    active = scratch / "active"
    next_dir = scratch / "next"
    old_private = keypair(active)
    next_private = keypair(next_dir)
    attack_private = ec.generate_private_key(ec.SECP256R1())
    internal = secrets.token_hex(32)
    values = {
        "MYSQL_ROOT_PASSWORD": secrets.token_hex(32), "MYSQL_PASSWORD": secrets.token_hex(32),
        "RABBIT_PASSWORD": secrets.token_hex(32), "RABBIT_USER": "jwt_e2e",
        "INTERNAL_KEY": internal, "WEBHOOK_SECRET": secrets.token_hex(32), "CIFRADO_LLAVE": secrets.token_hex(32),
        "JWT_KEY_DIRECTORY": active.as_posix(), "AUTH_JWKS_URI": urls["auth"] + "/.well-known/jwks.json",
        "DEMO_DATOS": "true", "INTEGRACION_API": "true", "ADMIN_CORREO": "admin@example.test",
        "PUBLIC_URL": urls["portal"], "COOKIE_SECURE": "false",
        "SMTP_HOST": "mailpit", "SMTP_PORT": "1025", "SMTP_AUTH": "false", "SMTP_STARTTLS": "false", "SMTP_SSL": "false",
        "BREVO_API_KEY": "", "XAI_API_KEY": "", "KHIPU_LLAVE": "", "KHIPU_SECRETO": "",
        "MERCADOPAGO_ACCESS_TOKEN": "", "MERCADOPAGO_ENVIRONMENT": "SIMULADA", "TRANSBANK_ENVIRONMENT": "SIMULADA",
        "BCENTRAL_USER": "", "BCENTRAL_PASS": "", "MAIL_FROM": "noreply@databridge.test",
    }
    env_file = scratch / "fixture.env"
    env_file.write_text("".join(f"{name}={value}\n" for name, value in values.items()), encoding="utf-8")
    override = scratch / "isolated.yml"
    lines = ["services:"]
    for service, image in (("ms-auth", "ms-auth"), ("ms-debt", "ms-debt"), ("ms-payments", "ms-payments"),
                           ("ms-ai", "ms-ai"), ("gateway", "gateway"), ("portal", "portal")):
        lines += [f"  {service}:", f"    image: tbridge/{image}:{args.image_tag}", f"    container_name: {project}-{service}",
                  "    restart: 'no'", "    pull_policy: never"]
        name = {"ms-auth": "auth", "ms-debt": "debt", "ms-payments": "payments", "portal": "portal"}.get(service)
        target = {"auth": 8081, "debt": 8083, "payments": 8084, "portal": 8080}
        if name:
            lines += [f"    ports: !override ['127.0.0.1:{ports[name]}:{target[name]}']"]
        if service == "ms-debt":
            lines += ["    environment:", "      APP_CONTACTO_CRON: '-'", "      APP_RECORDATORIOS_CRON: '-'"]
    for service in ("mysql", "rabbitmq", "mailpit"):
        lines += [f"  {service}:", f"    container_name: {project}-{service}", "    restart: 'no'"]
        if service == "mailpit":
            lines += [f"    ports: !override ['127.0.0.1:{ports['mailpit']}:8025']"]
        else:
            lines += ["    ports: !reset []"]
    lines += ["volumes:", "  tbridge_db_data:", f"    name: {project}-db"]
    override.write_text("\n".join(lines) + "\n", encoding="utf-8")
    before_cutover = scratch / "before-cutover.yml"
    before_cutover.write_text("services:\n  ms-auth:\n    environment:\n      SPRING_FLYWAY_TARGET: '6'\n", encoding="utf-8")
    compose = ["compose", "--project-name", project, "--project-directory", str(root), "--env-file", str(env_file),
               "--profile", "app", "-f", str(root / "docker-compose.yml"), "-f", str(override)]
    launched = False

    def sql(query):
        return command(["exec", "-i", project + "-mysql", "sh", "-c",
                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B tb_auth'], input_text=query)

    def login(identity, recipient):
        jar = CookieJar()
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect())
        code, messages = http(urls["mailpit"], "/api/v1/messages")
        expect(code, 200, "mailpit")
        previous = {message["ID"] for message in messages["messages"]}
        expect(http(urls["portal"], "/api/auth/codigo", method="POST", body=identity, opener=client)[0], 202, "pedir codigo")
        access_code = None
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline and access_code is None:
            _, messages = http(urls["mailpit"], "/api/v1/messages")
            for message in messages["messages"]:
                if message["ID"] in previous:
                    continue
                addresses = {address["Address"].lower() for address in message.get("To", [])}
                if recipient.lower() not in addresses:
                    continue
                _, mail = http(urls["mailpit"], "/api/v1/message/" + message["ID"])
                match = re.search(r"(?m)^([23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6})\s*$", mail.get("Text", ""))
                if match:
                    access_code = match.group(1)
                    break
            if access_code is None:
                time.sleep(0.25)  # Poll acotado de un servicio real, no espera de expiracion JWT.
        if access_code is None:
            raise RuntimeError("No llego el codigo al buzon de prueba")
        status, session = http(urls["portal"], "/api/auth/acceso", method="POST",
                               body={**identity, "codigo": access_code}, opener=client)
        expect(status, 200, "login")
        return client, session

    def private_token(private, kid, *, expired=False, include_kid=True, alg="ES256"):
        now = int(time.time())
        claims = {"sub": "16482337-7", "id": "16482337-7", "rut": "16482337-7", "role": "DEBTOR",
                  "iat": now - 901 if expired else now, "exp": now - 1 if expired else now + 900}
        return forged(private, claims, kid if include_kid else None, alg)

    def protected(token, expected=200):
        expect(http(urls["debt"], "/api/debts", token=token)[0], expected, "debt protegido")
        expect(http(urls["payments"], "/api/payments", token=token)[0], expected, "payments protegido")

    try:
        if command(["ps", "-aq", "--filter", "label=com.docker.compose.project=" + project]).strip():
            raise RuntimeError("El proyecto de pruebas ya existe; no se toca")
        for image in ("ms-auth", "ms-debt", "ms-payments", "ms-ai", "gateway", "portal"):
            command(["image", "inspect", "tbridge/" + image + ":" + args.image_tag, "--format", "{{.Id}}"])
        command(compose + ["config", "--quiet"])
        passed("compose_isolated")
        print("Arranque aislado hasta Flyway V6...", flush=True)
        launched = True
        command(compose + ["-f", str(before_cutover), "up", "-d", "--no-build", "--wait", "--wait-timeout", "600"])
        old_client, old_session = login({"rut": "16482337-7"}, "felipe.rojas@example.com")
        expect(old_session["user"]["role"], "DEBTOR", "rol previo")
        if int(sql("SELECT COUNT(*) FROM sessions WHERE revoked_at IS NULL;").strip()) < 1:
            raise RuntimeError("No se creo fixture de refresh anterior a V7")
        print("Aplicando corte V7 solo en el volumen nuevo...", flush=True)
        command(compose + ["up", "-d", "--no-deps", "--force-recreate", "--wait", "ms-auth"])
        expect(http(urls["portal"], "/api/auth/refresh", method="POST", opener=old_client)[0], 401, "refresh anterior")
        expect(int(sql("SELECT COUNT(*) FROM sessions WHERE revoked_at IS NULL;").strip()), 0, "revocacion inicial")
        passed("old_refresh_revoked")
        current = {}
        for role, identity, recipient in (
            ("DEBTOR", {"rut": "16482337-7"}, "felipe.rojas@example.com"),
            ("CREDITOR", {"correo": "camila.reyes@example.com"}, "camila.reyes@example.com"),
            ("ADMIN", {"correo": "admin@example.test"}, "admin@example.test"),
        ):
            client, session = login(identity, recipient)
            expect(session["user"]["role"], role, "rol login")
            status, me = http(urls["portal"], "/api/me", token=session["token"])
            expect(status, 200, "api me")
            expect(me["user"]["role"], role, "rol me")
            current[role] = (client, session)
            passed("login_" + role.lower())
        debtor = current["DEBTOR"][1]["token"]
        creditor = current["CREDITOR"][1]["token"]
        admin = current["ADMIN"][1]["token"]
        header = json.loads(base64.urlsafe_b64decode(debtor.split(".")[0] + "=="))
        expect(header["alg"], "ES256", "algoritmo")
        if not header.get("kid"):
            raise RuntimeError("kid ausente")
        old_kid = header["kid"]
        passed("es256_header_and_kid")
        _, jwks = http(urls["auth"], "/.well-known/jwks.json")
        if any("d" in key or key["kty"] != "EC" or key["crv"] != "P-256" for key in jwks["keys"]):
            raise RuntimeError("JWKS no es solo publico P-256")
        passed("public_jwks")
        protected(debtor)
        protected(creditor)
        expect(http(urls["debt"], "/api/admin/debt/reglas", token=admin)[0], 200, "ADMIN debt")
        expect(http(urls["debt"], "/api/debts", token=admin)[0], 403, "ADMIN fuera de portal debt")
        passed("debt_payments_roles")
        internal_headers = {"X-Internal-Key": internal, "Authorization": "Bearer not-a-jwt"}
        expect(http(urls["debt"], "/internal/cartera/76000000-0", headers=internal_headers)[0], 200, "internal")
        expect(http(urls["debt"], "/internal/cartera/76000000-0")[0], 401, "internal sin clave")
        passed("internal_key")
        status, api = http(urls["debt"], "/internal/claves", method="POST",
                           body={"rut": "76000000-0", "nombre": "JWT E2E"}, headers=internal_headers)
        expect(status, 200, "emitir api key")
        expect(http(urls["debt"], "/api/v1/cuenta", headers={"Authorization": "Bearer " + api["clave"]})[0], 200, "API v1")
        passed("api_v1")
        callback_status, _ = http(urls["payments"], "/api/payments/public/999999999?sig=invalid",
                                  headers={"Authorization": "Bearer not-a-jwt"})
        expect(callback_status, 404, "callback pasa a validacion de negocio sin JWT")
        passed("public_callbacks")
        for label, attack in (
            ("attack_hs256", private_token(old_private, old_kid, alg="HS256")),
            ("attack_wrong_ec_key", private_token(attack_private, old_kid)),
            ("attack_missing_kid", private_token(old_private, old_kid, include_kid=False)),
            ("attack_unknown_kid", private_token(old_private, "unknown")),
        ):
            protected(attack, 401)
            passed(label)
        (active / "verification").mkdir()
        shutil.copyfile(next_dir / "public.pem", active / "verification" / "next.pem")
        command(compose + ["up", "-d", "--no-deps", "--force-recreate", "--wait", "ms-auth"])
        _, prepublished = http(urls["auth"], "/.well-known/jwks.json")
        expect(len(prepublished["keys"]), 2, "prepublicacion")
        protected(debtor)
        shutil.copyfile(active / "public.pem", active / "verification" / "old.pem")
        shutil.copyfile(next_dir / "private.pem", active / "private.pem")
        shutil.copyfile(next_dir / "public.pem", active / "public.pem")
        (active / "verification" / "next.pem").unlink()
        command(compose + ["up", "-d", "--no-deps", "--force-recreate", "--wait", "ms-auth"])
        status, refreshed = http(urls["portal"], "/api/auth/refresh", method="POST", opener=current["DEBTOR"][0])
        expect(status, 200, "refresh nuevo sobrevive a reinicio/rotacion")
        new_token = refreshed["token"]
        new_header = json.loads(base64.urlsafe_b64decode(new_token.split(".")[0] + "=="))
        if new_header["kid"] == old_kid:
            raise RuntimeError("No cambio el firmante activo")
        protected(debtor)
        protected(new_token)
        passed("rotation_old_and_new_accepted")
        expired_old = private_token(old_private, old_kid, expired=True)
        protected(expired_old, 401)  # Expiracion simulada con firma real, sin esperar 15 min.
        (active / "verification" / "old.pem").unlink()
        command(compose + ["up", "-d", "--no-deps", "--force-recreate", "--wait", "ms-auth", "ms-debt", "ms-payments"])
        protected(expired_old, 401)
        protected(new_token)
        passed("rotation_expired_old_retired_new_valid")
        status, _ = http(urls["portal"], "/api/auth/logout", method="POST", opener=current["DEBTOR"][0])
        expect(status, 204, "logout")
        expect(http(urls["portal"], "/api/auth/refresh", method="POST", opener=current["DEBTOR"][0])[0], 401, "logout no renueva")
        passed("logout_refresh")
        summary["result"] = "PASS"
    except Exception as error:
        summary["result"] = "FAIL"
        summary["error"] = str(error)
        print("E2E FAIL: " + str(error), flush=True)
    finally:
        if launched:
            # Solo el proyecto aleatorio creado por esta ejecucion, nunca el Compose habitual.
            try:
                command(compose + ["down", "--volumes", "--remove-orphans"])
                summary["cleanup"] = "PASS"
            except Exception:
                summary["cleanup"] = "FAIL"
                summary["result"] = "FAIL"
        report.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        # No borrar recursivamente rutas externas. Scratch privado se conserva para revision.
        print("Informe sin credenciales: " + str(report), flush=True)
    return 0 if summary.get("result") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
