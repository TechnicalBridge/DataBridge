#!/usr/bin/env bash
# =============================================================================
#  render.sh — Gestión e integración con Render (render.com) para Bash / Git Bash
# =============================================================================

API_KEY="${RENDER_API_KEY:-rnd_XjD7eeY0omnHmK7oWIcJzlALNAet}"
BASE_URL="https://api.render.com/v1"

mostrar_ayuda() {
    echo "Uso: ./render.sh [opción]"
    echo ""
    echo "Opciones:"
    echo "  -Estado, --estado       Consulta el workspace y lista los servicios"
    echo "  -Validar, --validar     Valida la existencia y sintaxis de render.yaml"
    echo "  -Despliegues <id>       Muestra los últimos despliegues de un servicio"
    echo "  -Desplegar <id>         Dispara un nuevo despliegue para un servicio"
    echo "  -Ayuda, --help          Muestra esta ayuda"
    echo ""
}

accion="${1:-Estado}"
# Normalizar prefijos (- o --)
accion="${accion#--}"
accion="${accion#-}"
parametro="$2"

case "$accion" in
    Estado|estado)
        echo ""
        echo "========================================================"
        echo "  Estado del Workspace en Render (render.com)"
        echo "========================================================"
        
        # Consultar Owner / Workspace
        curl -s -H "Authorization: Bearer $API_KEY" -H "Accept: application/json" "$BASE_URL/owners" | \
        grep -o '"name":"[^"]*"' | head -n 1 | sed 's/"name":"/ Workspace : /;s/"//'
        
        curl -s -H "Authorization: Bearer $API_KEY" -H "Accept: application/json" "$BASE_URL/owners" | \
        grep -o '"email":"[^"]*"' | head -n 1 | sed 's/"email":"/ Email     : /;s/"//'

        echo ""
        echo "--- Servicios configurados ---"
        servicios=$(curl -s -H "Authorization: Bearer $API_KEY" -H "Accept: application/json" "$BASE_URL/services")
        if [ "$servicios" = "[]" ] || [ -z "$servicios" ]; then
            echo " No hay servicios creados aún en este workspace."
            echo " Conecta tu repositorio de GitHub en el dashboard de Render"
            echo " o sincroniza mediante el Blueprint 'render.yaml'."
        else
            echo "$servicios"
        fi
        echo ""
        ;;

    Validar|validar)
        echo ""
        echo "=== Validando Blueprint render.yaml ==="
        if [ -f "render.yaml" ]; then
            lineas=$(wc -l < render.yaml)
            echo " [OK] render.yaml encontrado ($lineas líneas)."
            if grep -q "services:" render.yaml; then
                echo " [OK] Sección 'services:' declarada correctamente."
            fi
            if grep -q "tbridge-portal" render.yaml && grep -q "tbridge-gateway" render.yaml; then
                echo " [OK] Portal y Gateway declarados."
            fi
        else
            echo " [ERROR] No se encontró render.yaml en el directorio actual."
        fi
        echo ""
        ;;

    Despliegues|despliegues)
        if [ -z "$parametro" ]; then
            echo "Error: Debes indicar el ID del servicio. Ejemplo: ./render.sh -Despliegues srv-xxxx"
            exit 1
        fi
        echo ""
        echo "=== Despliegues para el servicio: $parametro ==="
        curl -s -H "Authorization: Bearer $API_KEY" -H "Accept: application/json" "$BASE_URL/services/$parametro/deploys"
        echo ""
        ;;

    Desplegar|desplegar)
        if [ -z "$parametro" ]; then
            echo "Error: Debes indicar el ID del servicio. Ejemplo: ./render.sh -Desplegar srv-xxxx"
            exit 1
        fi
        echo ""
        echo "=== Disparando despliegue para: $parametro ==="
        curl -s -X POST -H "Authorization: Bearer $API_KEY" -H "Accept: application/json" -H "Content-Type: application/json" \
             -d '{}' "$BASE_URL/services/$parametro/deploys"
        echo ""
        ;;

    Ayuda|ayuda|help|h)
        mostrar_ayuda
        ;;

    *)
        echo "Opción no reconocida: $1"
        mostrar_ayuda
        exit 1
        ;;
esac
