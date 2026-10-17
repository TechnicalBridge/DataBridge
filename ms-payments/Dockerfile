# syntax=docker/dockerfile:1
# =============================================================================
#  ms-payments — la imagen del servicio de pagos.
# =============================================================================
#  Se construye desde la carpeta de este servicio, que no necesita nada
#  de los demas:
#
#      docker build -t tbridge/ms-payments ms-payments
#
#  El compose lo hace solo (perfil "app").
#
#  Dos etapas: la primera trae el JDK y Maven —unos 900 MB— y compila; la
#  segunda solo ejecuta el jar. La imagen final lleva el JRE y nada mas: ni
#  el codigo fuente ni las herramientas de compilacion viajan a produccion.
# =============================================================================

FROM maven:3.9-eclipse-temurin-25 AS construccion
WORKDIR /obra

#  Primero solo el pom, y se bajan sus dependencias. Esa capa queda guardada
#  y se reutiliza mientras el pom no cambie, aunque cambie el codigo. Antes lo
#  hacia un cache de ~/.m2 montado en la construccion, pero Railway rechaza
#  esos montajes si no llevan el id de cada servicio suyo, que cambia de un
#  proyecto a otro.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

#  Despues el codigo. Sin pruebas: las corre la integracion continua; una
#  imagen se construye para desplegar.
COPY src src
RUN mvn -B -q package -DskipTests


FROM eclipse-temurin:25-jre

#  curl es para el healthcheck, que corre dentro del contenedor.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

#  Nadie corre como root sin necesitarlo: si alguien se escapa del proceso, se
#  encuentra con un usuario sin permisos sobre nada.
RUN useradd --system --create-home --uid 10001 tbridge
WORKDIR /app
COPY --from=construccion /obra/target/*.jar app.jar
USER tbridge

#  La JVM toma el limite de memoria del contenedor, no el de la maquina: sin
#  esto pide memoria que no tiene y Docker la mata.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8084

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD curl -fs http://127.0.0.1:8084/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
