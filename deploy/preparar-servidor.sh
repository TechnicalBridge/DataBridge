#!/usr/bin/env bash
# =============================================================================
#  Prepara un VPS con Ubuntu 24.04 para DataBridge (#172). Se corre una vez,
#  como root, recien creado el servidor:
#
#    curl -fsSL https://raw.githubusercontent.com/TechnicalBridge/TB_web/main/deploy/preparar-servidor.sh \
#      | bash -s -- technicalbridge.site "ssh-ed25519 AAAA... llave-de-despliegue"
#
#  El primer argumento es el dominio; el segundo, la llave publica con que
#  GitHub Actions entra a desplegar. Deja:
#    - Docker y su plugin de compose, de los repositorios de Docker;
#    - el firewall abierto solo en 22, 80 y 443;
#    - las actualizaciones de seguridad automaticas, y 2 GB de swap;
#    - un usuario `databridge`, sin clave y sin root, que es el que despliega;
#    - el repositorio en /opt/databridge y un .env con claves nuevas al azar;
#    - un respaldo de la base cada madrugada (deploy/respaldar.sh).
#
#  Se puede volver a correr: no pisa el .env ni las claves que ya existen.
# =============================================================================
set -euo pipefail

DOMINIO="${1:?Falta el dominio: preparar-servidor.sh technicalbridge.site "ssh-ed25519 ..."}"
LLAVE_DESPLIEGUE="${2:?Falta la llave publica de despliegue}"
CARPETA=/opt/databridge
REPO=https://github.com/TechnicalBridge/TB_web.git

[ "$(id -u)" -eq 0 ] || { echo "Correlo como root" >&2; exit 1; }
export DEBIAN_FRONTEND=noninteractive

echo "== Paquetes y actualizaciones automaticas"
apt-get update -q
apt-get install -y -q ca-certificates curl git ufw unattended-upgrades openssl
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "== Docker, desde sus repositorios"
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

echo "== Firewall: solo SSH, HTTP y HTTPS"
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable
#  Docker publica puertos saltandose ufw; por eso el compose de produccion
#  solo publica los de Caddy (80 y 443).

echo "== 2 GB de swap, para que un pico de memoria no tumbe un servicio"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q /swapfile /etc/fstab || echo "/swapfile none swap sw 0 0" >> /etc/fstab
fi

echo "== El usuario databridge, que despliega"
id databridge >/dev/null 2>&1 || useradd -m -s /bin/bash databridge
usermod -aG docker databridge
install -d -m 700 -o databridge -g databridge /home/databridge/.ssh
touch /home/databridge/.ssh/authorized_keys
grep -qxF "$LLAVE_DESPLIEGUE" /home/databridge/.ssh/authorized_keys || echo "$LLAVE_DESPLIEGUE" >> /home/databridge/.ssh/authorized_keys
chown databridge:databridge /home/databridge/.ssh/authorized_keys
chmod 600 /home/databridge/.ssh/authorized_keys

echo "== El repositorio, en $CARPETA"
if [ ! -d "$CARPETA/.git" ]; then
  git clone -q "$REPO" "$CARPETA"
fi
chown -R databridge:databridge "$CARPETA"

echo "== El .env, con claves nuevas al azar"
ENV="$CARPETA/.env"
clave() { openssl rand -base64 48 | tr -d '\n=' | tr '+/' '-_'; }
poner() {  # poner VARIABLE valor: solo si no tiene valor
  if grep -qE "^$1=.+" "$ENV" 2>/dev/null; then return; fi
  sed -i "/^$1=/d" "$ENV" 2>/dev/null || true
  echo "$1=$2" >> "$ENV"
}
[ -f "$ENV" ] || install -m 600 -o databridge -g databridge /dev/null "$ENV"
for secreto in DB_AUTH_ROOT_PASSWORD DB_AUTH_PASSWORD DB_DEBT_ROOT_PASSWORD DB_DEBT_PASSWORD DB_PAYMENTS_ROOT_PASSWORD DB_PAYMENTS_PASSWORD RABBIT_PASSWORD INTERNAL_KEY WEBHOOK_SECRET CIFRADO_LLAVE; do
  poner "$secreto" "$(clave)"
done
# La firma ES256 se aprovisiona aparte; nunca se genera/rota en cada despliegue.
poner JWT_KEY_DIRECTORY "$CARPETA/.secrets/jwt"
if [ ! -r "$CARPETA/.secrets/jwt/private.pem" ] || [ ! -r "$CARPETA/.secrets/jwt/public.pem" ]; then
  echo "Falta aprovisionar la pareja P-256 de auth; ver docs/seguridad/jwt.md (permisos para UID 10001)." >&2
fi
poner DOMINIO "$DOMINIO"
poner PUBLIC_URL "https://$DOMINIO"
poner CORS_ORIGINS "https://$DOMINIO"
poner COOKIE_SECURE true
poner DEMO_DATOS true
poner KHIPU_URL_AVISOS "https://$DOMINIO/api/payments/public/khipu/aviso"
#  Para la demo, la ley no frena los codigos (#199). En produccion, false.
poner CONTACTO_SIN_HORARIO true
poner CONTACTO_SIN_LIMITE true
poner RATE_AUTH_CAPACITY 30
chown databridge:databridge "$ENV"
chmod 600 "$ENV"

echo "== El respaldo de la base, cada madrugada"
chmod +x "$CARPETA/deploy/respaldar.sh"
echo "30 4 * * * root $CARPETA/deploy/respaldar.sh" > /etc/cron.d/databridge-respaldo

cat <<FIN

Listo. Falta, desde el equipo de quien despliega (docs/despliegue/README.md):
  1. Copiar a $ENV las claves del correo y de las pasarelas
     (BREVO_API_KEY, MAIL_FROM, CORREO_SOLO_A, CORREO_DESVIAR_A, ADMIN_CORREO,
     KHIPU_LLAVE, KHIPU_SECRETO, MERCADOPAGO_*), sin mostrarlas.
  2. Apuntar el DNS de $DOMINIO (y www) a la IP de este servidor.
  3. En GitHub, los secretos del despliegue y la variable DESPLIEGUE_HOST.
     Desde ahi, cada push a main se despliega solo.
FIN
