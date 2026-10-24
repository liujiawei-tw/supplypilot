import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  define: { 'import.meta.env.VITE_API_BASE_URL': JSON.stringify('http://localhost:8080/api/v1') },
  test: {
    environment: 'jsdom',
    setupFiles: ['./tests/setup.ts'],
    // Ant Design's JSDOM suites are CPU-heavy and share finite process resources.
    // Use half of the available parallelism; evaluate suite stability in a subsequent Issue.
    maxWorkers: '50%',
    testTimeout: 30_000,
  },
});
