import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: '/admin/',
  plugins: [react()],
  // TinyMCE 4's lightgray skin carries IE-only star hacks (*zoom); recovery drops them with a warning
  css: { lightningcss: { errorRecovery: true } },
});
