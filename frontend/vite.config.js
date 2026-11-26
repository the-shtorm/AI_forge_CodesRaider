import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/v1": process.env.SPRING_BASE_URL || "http://localhost:8080",
      "/health": process.env.SPRING_BASE_URL || "http://localhost:8080",
    },
  },
});
