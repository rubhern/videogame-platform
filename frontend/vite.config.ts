import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

import { version as frontendVersion } from "./package.json";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: {
    __APPLICATION_VERSION__: JSON.stringify(process.env.APPLICATION_VERSION ?? frontendVersion),
    __SOURCE_REVISION__: JSON.stringify(process.env.SOURCE_REVISION ?? "local-development"),
  },
  server: {
    proxy: {
      "/actuator": "http://localhost:8080",
      "/api": "http://localhost:8080",
      "/auth": "http://localhost:8080",
      "/login": "http://localhost:8080",
    },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./src/test/setup.ts"],
    css: true,
  },
});
