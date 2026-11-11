declare const process: { env: Record<string, string | undefined> };
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dashboard is local-only: the dev server proxies /api to the api (JOBRADAR_API_URL, default 127.0.0.1:8080).
export default defineConfig({
  plugins: [react()],
  server: { host: "127.0.0.1", port: 5173, proxy: { "/api": process.env.JOBRADAR_API_URL ?? "http://127.0.0.1:8080" } },
});
