/** The signed-in user, runtime configuration, branding and the permission catalogue. */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request, UnauthorizedError } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type Me = components['schemas']['MeService.Me'];
export type AccessState = Me['accessState'];
export type Meta = components['schemas']['MeController.Meta'];
export type Branding = components['schemas']['MeController.Branding'];
export type PermissionView = components['schemas']['AccessViews.PermissionView'];

/** The user, or null before sign-in (401 is the expected answer then, so it never redirects). */
export function useMe() {
  return useQuery({
    queryKey: queryKeys.me,
    queryFn: async ({ signal }) => {
      try {
        return await request<Me>('/me', { signal, allowUnauthorized: true });
      } catch (error) {
        if (error instanceof UnauthorizedError) return null;
        throw error;
      }
    },
    staleTime: 30_000,
    retry: false,
  });
}

export function useMeta(enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.meta,
    queryFn: ({ signal }) => request<Meta>('/meta', { signal }),
    enabled,
    staleTime: 5 * 60_000,
  });
}

/** Public: the sign-in screen's organisation name and logo. */
export function useBranding() {
  return useQuery({
    queryKey: queryKeys.branding,
    queryFn: ({ signal }) => request<Branding>('/public/branding', { signal, allowUnauthorized: true }),
    staleTime: 5 * 60_000,
  });
}

export function usePermissionCatalogue(enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.permissions,
    queryFn: ({ signal }) => request<PermissionView[]>('/permissions', { signal }),
    enabled,
    staleTime: Infinity,
  });
}

/** "Stay signed in" on the idle warning, and throttled activity (F9). */
export function keepalive(): Promise<void> {
  return request<undefined>('/me/keepalive', { method: 'POST' });
}

export function useRequestAccess() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (note: string) =>
      request<components['schemas']['AccessViews.AccessRequestView']>('/me/access-request', {
        method: 'POST',
        body: { note },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.me }),
  });
}

/** POST /logout (CSRF), then the sign-in screen; the caller navigates. */
export async function signOut(): Promise<void> {
  try {
    await request<undefined>('/logout', { method: 'POST', root: true, allowUnauthorized: true });
  } catch (error) {
    if (!(error instanceof UnauthorizedError)) throw error;
  }
}
