import { defineConfig, loadEnv } from "vite";
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const entorno = loadEnv(mode, fileURLToPath(new URL("..", import.meta.url)), "");
  const destino = entorno.DEV_PROXY_TARGET || `http://127.0.0.1:${entorno.PORTAL_PORT || "8080"}`;
  const url = new URL(destino);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error("DEV_PROXY_TARGET debe ser una URL HTTP o HTTPS sin credenciales.");
  }
  return {
  plugins: [react()],
  server: {
    port: 5173,
    //  Igual que nginx en Docker: la API y su documentacion van al gateway.
    proxy: {
      "/api": destino,
      "/v3/api-docs": destino,
      "/swagger-ui": destino,
      "/webjars": destino,
    },
  },
  };
});
