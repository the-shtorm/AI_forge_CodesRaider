import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

const spring = process.env.SPRING_BASE_URL || "http://localhost:8080";

function publicPages(directory) {
  return (server) => {
    server.middlewares.use((request, _response, next) => {
      const url = new URL(request.url, "http://localhost");
      if (url.pathname !== "/" && url.pathname.endsWith("/")) {
        const page = `${url.pathname}index.html`;
        const file = new URL(`.${page}`, directory);
        if (file.href.startsWith(directory.href) && existsSync(fileURLToPath(file))) {
          request.url = page + url.search;
        }
      }
      next();
    });
  };
}

export default defineConfig({
  appType: "mpa",
  build: { emptyOutDir: true },
  plugins: [{
    name: "public-storefront-pages",
    configureServer: publicPages(new URL("./public/", import.meta.url)),
    configurePreviewServer: publicPages(new URL("./dist/", import.meta.url)),
  }],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/health": spring,
      "/v1": spring,
    },
  },
});
