#!/usr/bin/env bash
# =============================================================================
#  Respaldo de las tres bases de DataBridge en el servidor (#172). Lo corre
#  cron cada madrugada (ver preparar-servidor.sh); guarda los ultimos 7 dias
#  en /var/backups/databridge. Para restaurar uno:
#
#    gunzip -c /var/backups/databridge/2026-10-20.sql.gz \
#      | docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" tbridge-db mysql -uroot
# =============================================================================
set -euo pipefail

DESTINO=/var/backups/databridge
CLAVE=$(grep -E '^MYSQL_ROOT_PASSWORD=' /opt/databridge/.env | cut -d= -f2-)
mkdir -p "$DESTINO"
chmod 700 "$DESTINO"

archivo="$DESTINO/$(date +%F).sql.gz"
docker exec -e MYSQL_PWD="$CLAVE" tbridge-db \
  mysqldump -uroot --single-transaction --routines --triggers --databases tb_auth tb_debt tb_payments \
  | gzip > "$archivo.tmp"
mv "$archivo.tmp" "$archivo"
chmod 600 "$archivo"

find "$DESTINO" -name '*.sql.gz' -mtime +7 -delete
