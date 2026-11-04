/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: '/admin/',
  plugins: [react()],
  test: {
    // jsdom, not happy-dom: hpq reads attributes as named properties of NamedNodeMap (attributes.src), which happy-dom
    // does not provide
    environment: 'jsdom',
    environmentOptions: { jsdom: { url: 'http://localhost/' } },
    include: ['src/**/*.test.{ts,tsx}'],
    // Gutenberg's packages import JSON without an import attribute, which Node's loader refuses; Vite transforms them
    server: { deps: { inline: [/@wordpress\//] } },
    // loading Gutenberg through Vite takes seconds, more while the other files run alongside
    testTimeout: 30_000,
    hookTimeout: 30_000,
    coverage: { include: ['src/**/*.{ts,tsx}'], exclude: ['src/**/*.test.{ts,tsx}', 'src/testapi.ts', 'src/editor/testwxr.ts'] },
  },
});
