#!/usr/bin/env bash
# =============================================================================
#  Respaldo de las tres bases de DataBridge en el servidor (#172). Lo corre
#  cron cada madrugada (ver preparar-servidor.sh); guarda los ultimos 7 dias
#  en /var/backups/databridge. Desde #238 cada base vive en su propio MySQL,
#  asi que va un archivo por base y por dia. Para restaurar uno, con la clave
#  de root de esa base (DB_DEBT_ROOT_PASSWORD para tb_debt, ...):
#
#    gunzip -c /var/backups/databridge/2026-10-20-tb_debt.sql.gz \
#      | docker exec -i -e MYSQL_PWD="$DB_DEBT_ROOT_PASSWORD" tbridge-db-debt mysql -uroot
# =============================================================================
set -euo pipefail

DESTINO=/var/backups/databridge
ENV=/opt/databridge/.env
mkdir -p "$DESTINO"
chmod 700 "$DESTINO"

respaldar() {  # respaldar BASE CONTENEDOR VARIABLE_DE_LA_CLAVE
  local clave archivo
  clave=$(grep -E "^$3=" "$ENV" | cut -d= -f2-)
  archivo="$DESTINO/$(date +%F)-$1.sql.gz"
  docker exec -e MYSQL_PWD="$clave" "$2" \
    mysqldump -uroot --single-transaction --routines --triggers --hex-blob --databases "$1" \
    | gzip > "$archivo.tmp"
  mv "$archivo.tmp" "$archivo"
  chmod 600 "$archivo"
}

respaldar tb_auth     tbridge-db-auth     DB_AUTH_ROOT_PASSWORD
respaldar tb_debt     tbridge-db-debt     DB_DEBT_ROOT_PASSWORD
respaldar tb_payments tbridge-db-payments DB_PAYMENTS_ROOT_PASSWORD

find "$DESTINO" -name '*.sql.gz' -mtime +7 -delete
