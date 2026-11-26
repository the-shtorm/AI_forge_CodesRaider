import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const spring = process.env.SPRING_BASE_URL || "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/health": spring,
      "/v1": spring,
    },
  },
});
