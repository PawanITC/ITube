import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    open: true,
    // Forward /api calls to the Spring Boot backend so the browser sees one origin (no CORS setup needed in dev).
    proxy: {
      // Trailing slash matters: '/api' alone would also swallow the /api-docs page.
      '/api/': 'http://localhost:8081',
    },
  },
  build: {
    outDir: 'build',
    // Swagger UI is ~1.3 MB on its own; it is lazy-loaded, so this is expected.
    chunkSizeWarningLimit: 1500,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/setupTests.js',
  },
});
