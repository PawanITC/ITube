import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const cdnBase = process.env.VITE_CDN_BASE_URL?.replace(/\/+$/, '');
if (cdnBase && !/^https:\/\/[a-zA-Z0-9.-]+$/.test(cdnBase)) {
  throw new Error('VITE_CDN_BASE_URL must be an HTTPS origin without a path');
}

export default defineConfig({
  // Only bundled assets use the CDN. Public files and API URLs stay on the app origin.
  experimental: {
    renderBuiltUrl(filename, { type }) {
      if (cdnBase && type === 'asset') return `${cdnBase}/${filename}`;
      return { relative: false };
    },
  },
  plugins: [react()],
  server: {
    port: 3000,
    open: true,
    // Forward /api calls to the Spring Boot backend so the browser sees one origin (no CORS setup needed in dev).
    proxy: {
      '/api/': 'http://localhost:8080',
    },
  },
  build: {
    outDir: 'build',
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/setupTests.js',
  },
});
