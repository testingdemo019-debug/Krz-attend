/**
 * Providers (F4): TanStack Query (30 s stale by default), the confirm dialog host, the toaster; and the API client's
 * global reactions: 401 → sign-in with returnTo (relative), 429 → a toast with the wait.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router';
import { onApiError, safeReturnTo } from '@/api/client';
import { queryKeys } from '@/api/queryKeys';
import { ConfirmHost } from '@/components/feedback/ConfirmDialog';
import { toast } from '@/components/feedback/toast';
import { Toaster } from '@/components/feedback/Toaster';
import { router } from './router';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: true },
    mutations: { retry: false },
  },
});

onApiError('unauthorized', error => {
  if (router.state.location.pathname === '/signin') return;
  const here = safeReturnTo(router.state.location.pathname + router.state.location.search);
  const notice = error.code === 'SESSION_EXPIRED' ? 'notice=expired&' : '';
  queryClient.setQueryData(queryKeys.me, null);
  void router.navigate(`/signin?${notice}${here ? `returnTo=${encodeURIComponent(here)}` : ''}`, { replace: true });
});

onApiError('rateLimited', error => {
  toast(error.message, { type: 'warn' });
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ConfirmHost>
        <RouterProvider router={router} />
      </ConfirmHost>
      <Toaster />
    </QueryClientProvider>
  );
}
