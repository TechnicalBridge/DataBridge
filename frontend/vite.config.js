import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    include: ["tests/*.test.jsx"],
    restoreMocks: true,
  },
  server: {
    port: 5173,
    //  Igual que nginx en Docker: la API y su documentacion van al gateway.
    proxy: {
      "/api": "http://localhost:8082",
      "/v3/api-docs": "http://localhost:8082",
      "/swagger-ui": "http://localhost:8082",
      "/webjars": "http://localhost:8082",
    },
  },
});
