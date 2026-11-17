import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

import { version as frontendVersion } from "./package.json";

const backend = process.env.GAMEOMETRO_DEV_BACKEND ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: {
    __APPLICATION_VERSION__: JSON.stringify(process.env.APPLICATION_VERSION ?? frontendVersion),
    __SOURCE_REVISION__: JSON.stringify(process.env.SOURCE_REVISION ?? "local-development"),
  },
  server: {
    proxy: {
      "/actuator": backend,
      "/api": backend,
      "/auth": backend,
      "/login": backend,
    },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./src/test/setup.ts"],
    css: true,
  },
});
