# =============================================================================
#  El portal: se compila con Node y se sirve con nginx.
# =============================================================================
#  En desarrollo Vite sirve el codigo y recarga al guardar. Eso no sirve para
#  desplegar: aqui se compila una vez a HTML, CSS y JavaScript, y los sirve
#  nginx, que para eso esta hecho. La imagen final no lleva Node.
# =============================================================================

FROM node:22-alpine AS construccion
WORKDIR /obra

#  package.json y el candado primero: mientras no cambien, Docker se salta
#  la instalacion entera.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build


#  La imagen oficial de nginx arranca su proceso maestro como root. Esta es la
#  misma nginx preparada para no hacerlo: corre como el usuario 101 y escucha
#  en el 8080, porque un proceso sin privilegios no puede tomar el puerto 80.
FROM nginxinc/nginx-unprivileged:alpine
COPY --from=construccion /obra/dist /usr/share/nginx/html

#  La configuracion es una plantilla que la imagen completa al arrancar (ver
#  nginx.conf.template). Estos son sus valores por omision, los de Docker
#  Compose; en el servidor, docker-compose.prod.yml cambia PROXY_DE_ENTRADA.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV GATEWAY_HOST=gateway \
    GATEWAY_PORT=8082 \
    PROXY_DE_ENTRADA=127.0.0.1 \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1

EXPOSE 8080
