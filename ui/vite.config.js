import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev the UI calls /api/*, which Vite forwards to the Spring Boot service (no CORS setup needed).
// For a real deployment serve the built UI and the API from the same origin behind a reverse proxy.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.LEDGER_API_URL || 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
});
