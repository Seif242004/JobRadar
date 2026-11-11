import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dashboard is local-only: the dev server proxies /api to the api running on 127.0.0.1:8080.
export default defineConfig({
  plugins: [react()],
  server: { host: "127.0.0.1", port: 5173, proxy: { "/api": "http://127.0.0.1:8080" } },
});
