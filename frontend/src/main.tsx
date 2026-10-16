import '@/styles/cascade';
import { setNonce } from 'get-nonce';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/app/App';
import { applyCompactRows } from '@/lib/compact';
import { applyTheme } from '@/lib/theme';

// Before the first render: the theme and the row density (no flash), and the CSP nonce for styles injected at run
// time (Radix's scroll lock, F11). nginx replaces __CSP_NONCE__ per response; Vite's dev server uses a fixed value.
applyTheme();
applyCompactRows();
const nonce = document.querySelector<HTMLMetaElement>('meta[name="csp-nonce"]')?.content;
if (nonce) setNonce(nonce);

const root = document.getElementById('app');
if (root) {
  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}
