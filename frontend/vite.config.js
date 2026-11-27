import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const spring = process.env.SPRING_BASE_URL || "http://localhost:8080";

export default defineConfig({
  plugins: [
    react(),
    {
      name: "vite-development-csp",
      apply: "serve",
      transformIndexHtml: (html) => html.replace("script-src 'self'", "script-src 'self' 'unsafe-inline'"),
    },
  ],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { "/health": spring, "/v1": spring },
  },
});
