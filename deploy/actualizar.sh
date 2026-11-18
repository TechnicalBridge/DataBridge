#!/usr/bin/env bash
# =============================================================================
#  Pone el servidor en una version de main (#470): respalda las bases, baja
#  las imagenes de esa version y levanta todo. Si la version nueva no levanta,
#  vuelve a la que estaba. Corre como el usuario databridge. Lo usan:
#
#    - el workflow Desplegar, con el commit que acaba de entrar a main:
#        bash deploy/actualizar.sh <commit>
#    - el arranque de la maquina (deploy/databridge-actualizar.service), sin
#      version: busca la ultima de main con imagenes publicadas, por si hubo
#      merges mientras la maquina estaba apagada;
#    - el workflow, si la prueba de humo falla, para volver a la anterior:
#        bash deploy/actualizar.sh --volver
#
#  El respaldo queda en /var/backups/databridge, con la version de destino en
#  el nombre (ver deploy/respaldar.sh para restaurarlo).
# =============================================================================
set -euo pipefail

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile app)
IMAGEN=ghcr.io/technicalbridge/databridge
ANTERIOR="$HOME/.databridge-version-anterior"
#  Uno a la vez: al prender la maquina corre el servicio de arranque, y alguien
#  puede lanzar Desplegar justo entonces. El segundo espera a que termine el
#  primero (#482).
CANDADO="$HOME/.databridge-actualizar.lock"

levantar() {  # levantar VERSION
  export VERSION="$1"
  "${COMPOSE[@]}" pull -q && "${COMPOSE[@]}" up -d --no-build --wait --wait-timeout 600
}

main() {
  exec 9>"$CANDADO"
  if ! flock -n 9; then
    echo "== Otra actualizacion esta corriendo: se espera a que termine"
    flock 9
  fi
  cd /opt/databridge
  local actual destino anotar=true
  actual=$(git rev-parse HEAD)
  git fetch -q origin main

  if [ "${1:-}" = "--volver" ]; then
    destino=$(cat "$ANTERIOR" 2>/dev/null || true)
    [ -n "$destino" ] || { echo "No hay una version anterior anotada" >&2; return 1; }
    anotar=false
  elif [ -n "${1:-}" ]; then
    destino=$(git rev-parse "$1^{commit}")
  else
    #  Al arrancar: el commit mas nuevo de main que ya tenga sus imagenes. Un
    #  cambio solo de documentacion no compila imagenes y se salta.
    destino=""
    for commit in $(git rev-list -n 30 origin/main); do
      if docker manifest inspect "$IMAGEN-gateway:$commit" >/dev/null 2>&1; then
        destino=$commit
        break
      fi
    done
    if [ -z "$destino" ]; then
      echo "Ningun commit reciente de main tiene imagenes publicadas: se queda en ${actual:0:7}"
      return 0
    fi
    if [ "$destino" = "$actual" ]; then
      echo "Ya esta en la ultima version de main (${actual:0:7})"
      return 0
    fi
  fi

  echo "== De ${actual:0:7} a ${destino:0:7}"
  git reset -q --hard "$destino"

  #  Las migraciones corren al arrancar los servicios: el respaldo va antes.
  "${COMPOSE[@]}" up -d --no-build --wait db-auth db-debt db-payments
  if ! ETIQUETA="$(date +%F-%H%M)-antes-de-${destino:0:7}" bash deploy/respaldar.sh; then
    git reset -q --hard "$actual"
    echo "== No se pudo respaldar las bases: no se despliega" >&2
    return 1
  fi

  if levantar "$destino"; then
    if [ "$anotar" = true ] && [ "$destino" != "$actual" ]; then
      echo "$actual" > "$ANTERIOR"
    fi
    docker image prune -f >/dev/null
    echo "== Listo: ${destino:0:7}"
    return 0
  fi

  echo "== ${destino:0:7} no levanto: se vuelve a ${actual:0:7}" >&2
  git reset -q --hard "$actual"
  levantar "$actual" || echo "== Tampoco levanto ${actual:0:7}: hay que revisarlo a mano" >&2
  return 1
}

#  En una sola linea: bash ya leyo todo antes de que git reset cambie este archivo.
main "$@"; exit $?
