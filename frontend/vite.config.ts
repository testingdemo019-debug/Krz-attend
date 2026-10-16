/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';

const BACKEND = 'http://localhost:8080';
const MEDIA_RELAY = 'http://127.0.0.1:1984';
const DEV_CSP_NONCE = 'dev-nonce';
/** Dropped from production bundles (F11); console.error stays. */
const STRIPPED_CONSOLE = [
  'log',
  'info',
  'debug',
  'warn',
  'trace',
  'table',
  'dir',
  'group',
  'groupEnd',
  'time',
  'timeEnd',
];

/**
 * The go2rtc API basic-auth header for the dev proxy, read from deploy/.env on the Node side only. It never reaches
 * client code: no `define`, no `import.meta.env` (DECISIONS D-082). Null when the file or the values are missing.
 */
function go2rtcAuthorization(): string | null {
  let text: string;
  try {
    text = readFileSync(fileURLToPath(new URL('../deploy/.env', import.meta.url)), 'utf8');
  } catch {
    return null;
  }
  const env = new Map<string, string>();
  for (const line of text.split(/\r?\n/)) {
    const match = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
    if (match?.[1] && match[2] !== undefined) env.set(match[1], match[2].replace(/^(['"])(.*)\1$/, '$2'));
  }
  const user = env.get('GO2RTC_API_USER');
  const password = env.get('GO2RTC_API_PASSWORD');
  return user && password ? `Basic ${Buffer.from(`${user}:${password}`).toString('base64')}` : null;
}

/** In development the CSP nonce placeholder gets a fixed value; nginx replaces it per response in production. */
function devCspNonce(): Plugin {
  return {
    name: 'krz-dev-csp-nonce',
    apply: 'serve',
    transformIndexHtml: html => html.replace('__CSP_NONCE__', DEV_CSP_NONCE),
  };
}

/**
 * `vite --mode mock` (parity and E2E): index.html loads test/mock/entry.ts, which starts MSW on the prototype
 * fixtures; the MSW service worker is served from the package. Development and production builds never see either.
 */
function mockMode(): Plugin {
  return {
    name: 'krz-mock-mode',
    apply: (_, env) => env.mode === 'mock' && env.command === 'serve',
    transformIndexHtml: html => html.replace('/src/main.tsx', '/test/mock/entry.ts'),
    configureServer(server) {
      const worker = fileURLToPath(new URL('./node_modules/msw/lib/mockServiceWorker.js', import.meta.url));
      server.middlewares.use('/mockServiceWorker.js', (_request, response) => {
        response.setHeader('Content-Type', 'text/javascript');
        response.end(readFileSync(worker));
      });
    },
  };
}

export default defineConfig(({ command, mode }) => {
  const backend = { target: BACKEND, xfwd: true, changeOrigin: false };
  const authorization = command === 'serve' ? go2rtcAuthorization() : null;
  return {
    plugins: [react(), devCspNonce(), mockMode()],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    // The prototype's class names are kept as they are (D-081): modules give each component its own file, while
    // descendant selectors across components (.input-group .btn, .tile-head .icon-btn) keep working.
    css: { modules: { generateScopedName: '[local]' } },
    // Every module is scanned at start, so a lazy page never makes the dev server re-optimise and reload mid-session.
    optimizeDeps: { entries: ['index.html', 'src/**/*.tsx', 'test/mock/entry.ts'] },
    server: {
      host: 'localhost',
      port: 5173,
      strictPort: true,
      // Mock mode answers the API with MSW and has no backend or media relay behind it.
      proxy:
        mode === 'mock'
          ? {}
          : {
              '/api': backend,
              '/oauth2': backend,
              '/login/oauth2': backend,
              '/logout': backend,
              // Live video only: go2rtc's MSE WebSocket. No auth_request in development; go2rtc listens on 127.0.0.1.
              '/media/api/ws': {
                target: MEDIA_RELAY,
                ws: true,
                rewrite: path => path.replace(/^\/media/, ''),
                configure: proxy => {
                  proxy.on('proxyReqWs', request => {
                    if (authorization) request.setHeader('Authorization', authorization);
                  });
                },
              },
            },
    },
    build: {
      sourcemap: false,
      cssCodeSplit: false,
      rolldownOptions: {
        treeshake: { manualPureFunctions: STRIPPED_CONSOLE.map(name => `console.${name}`) },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}', 'test/unit/**/*.test.{ts,tsx}'],
      css: { modules: { classNameStrategy: 'non-scoped' } },
      restoreMocks: true,
    },
  };
});
