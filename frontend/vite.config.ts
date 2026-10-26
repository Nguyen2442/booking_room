import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev the API is proxied, so the browser sees one origin exactly like behind nginx in production.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
});
