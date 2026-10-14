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

#  nginx.conf es una plantilla: al arrancar, la imagen sustituye las variables
#  con envsubst y escribe el resultado en /etc/nginx/conf.d/. Los valores por
#  defecto son los de docker compose; en otras plataformas (Railway) se
#  sobreescriben con variables de entorno. El filtro hace que envsubst toque
#  solo estas tres y deje en paz las variables propias de nginx ($uri, $host...).
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV NGINX_RESOLVER=127.0.0.11 \
    GATEWAY_HOST=gateway \
    GATEWAY_PORT=8082 \
    NGINX_ENVSUBST_FILTER=^(NGINX_RESOLVER|GATEWAY_HOST|GATEWAY_PORT)\$

EXPOSE 8080
