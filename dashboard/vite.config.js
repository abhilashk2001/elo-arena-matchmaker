import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev the dashboard runs on Vite's server (5173) and the backend on 8080. We proxy the read
// and admin paths to the backend so the browser makes same-origin calls and we avoid CORS config
// on the server. The backend base is overridable for running against another host.
const backend = process.env.ELOARENA_API_URL || 'http://localhost:8080';

export default defineConfig({
  // Served at root in the compose/nginx build and in dev; the GitHub Pages build sets VITE_BASE to
  // the project-page subpath (/elo-arena-matchmaker/) so asset URLs resolve there.
  base: process.env.VITE_BASE || '/',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/dashboard': backend,
      '/api': backend,
      '/actuator': backend,
    },
  },
});
