/// <reference types="vitest" />
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

// The Java server serves dist/ same-origin in production. In development the Vite server proxies
// the backend paths to a locally running server on :8080, so cookies and CSRF behave the same.
const backend = 'http://localhost:8080';

/**
 * Preloads the Latin display and body fonts. Without it the condensed headline first paints in
 * the fallback face and re-wraps when the webfont lands, which shows up as layout shift.
 */
function preloadFonts(): Plugin {
  const wanted = /^assets\/(big-shoulders-display-latin-wght-normal|atkinson-hyperlegible-latin-(400|700)-normal)-[\w-]+\.woff2$/;
  return {
    name: 'preload-fonts',
    apply: 'build',
    transformIndexHtml: {
      order: 'post',
      handler(_html, ctx) {
        return Object.keys(ctx.bundle ?? {})
          .filter((f) => wanted.test(f))
          .map((f) => ({
            tag: 'link',
            attrs: { rel: 'preload', href: `/${f}`, as: 'font', type: 'font/woff2', crossorigin: '' },
            injectTo: 'head' as const,
          }));
      },
    },
  };
}

export default defineConfig({
  plugins: [react(), preloadFonts()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
    // The CSP forbids inline scripts; Vite's preload polyfill is the only inline-ish helper.
    modulePreload: { polyfill: false },
    sourcemap: false,
    target: 'es2020',
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/mock-gateway': { target: backend, changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
