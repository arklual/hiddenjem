/// <reference types="vitest" />
import { fileURLToPath } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import type { ProxyOptions } from 'vite';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  // `loadEnv` comes from vite itself; vitest re-exports only `defineConfig`, and importing it from
  // there fails at runtime with an unhelpful "does not provide an export named" error.
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080';

  const apiProxy: ProxyOptions = {
    target: apiTarget,
    changeOrigin: true,
    // SSE (`GET /research-requests/{id}/events`) must stream through unbuffered.
    // Forcing identity encoding stops the dev proxy from accumulating gzip frames,
    // so `text/event-stream` chunks reach the browser immediately.
    configure: (proxy) => {
      proxy.on('proxyReq', (proxyReq) => {
        proxyReq.setHeader('Accept-Encoding', 'identity');
      });
    },
  };

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      // `fileURLToPath` rather than `.pathname`: the latter leaves URL-encoded characters in the
      // path, which breaks resolution for any checkout directory containing a space.
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      strictPort: false,
      proxy: {
        '/api': apiProxy,
      },
    },
    preview: {
      port: 3000,
    },
    build: {
      target: 'es2022',
      // `hidden` still emits the map for an error tracker to consume out of band, but omits the
      // //# sourceMappingURL comment, so the browser never fetches it. `sourcemap: true` served
      // the complete original source — including the comments describing the auth design — to
      // any anonymous visitor.
      sourcemap: mode === 'production' ? 'hidden' : true,
      rollupOptions: {
        output: {
          manualChunks: {
            react: ['react', 'react-dom', 'react-router-dom'],
            query: ['@tanstack/react-query'],
            zod: ['zod'],
          },
        },
      },
    },
    css: {
      modules: {
        localsConvention: 'camelCaseOnly',
        generateScopedName: mode === 'production' ? '[hash:base64:6]' : '[name]__[local]',
      },
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      include: ['src/**/*.{test,spec}.{ts,tsx}'],
      restoreMocks: true,
    },
  };
});
