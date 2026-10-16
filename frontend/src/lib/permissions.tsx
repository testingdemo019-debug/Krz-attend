/**
 * What the signed-in user may do (F4): permissions come from /me, labels from the catalogue. No code checks role
 * names; a user's permissions are the union of their roles' permissions, computed by the backend.
 */
import { createContext, useContext, useMemo, type ReactNode } from 'react';
import type { Me, PermissionView } from '@/api/me';
import type { components } from '@/api/schema';

export type PermissionKey = components['schemas']['AccessController.CreateRoleRequest']['permissions'][number];

interface Access {
  me: Me;
  granted: ReadonlySet<string>;
  labels: ReadonlyMap<string, string>;
}

const AccessContext = createContext<Access | null>(null);

export function AccessProvider({
  me,
  catalogue,
  children,
}: {
  me: Me;
  catalogue: readonly PermissionView[];
  children: ReactNode;
}) {
  const value = useMemo<Access>(
    () => ({
      me,
      granted: new Set(me.permissions),
      labels: new Map(catalogue.map(p => [p.key, p.label])),
    }),
    [me, catalogue],
  );
  return <AccessContext value={value}>{children}</AccessContext>;
}

function useAccess(): Access {
  const access = useContext(AccessContext);
  if (!access) throw new Error('useAccess outside AccessProvider');
  return access;
}

export function useMeUser(): Me {
  return useAccess().me;
}

export function useCan(permission: PermissionKey): boolean {
  return useAccess().granted.has(permission);
}

export function useCanAny(...permissions: PermissionKey[]): boolean {
  const { granted } = useAccess();
  return permissions.some(p => granted.has(p));
}

/** A checker for code that tests several permissions (navigation, route guards). */
export function usePermissionCheck(): (permission: PermissionKey) => boolean {
  const { granted } = useAccess();
  return permission => granted.has(permission);
}

/** The prototype's needsPermissionText: `Needs the “View live camera” permission — ask a System Administrator`. */
export function useNeedsPermissionText(): (permission: PermissionKey) => string {
  const { labels } = useAccess();
  return permission => `Needs the “${labels.get(permission) ?? permission}” permission — ask a System Administrator`;
}

/** Numeric similarity scores are shown only with settings.recognition (the prototype's showScores). */
export function useShowScores(): boolean {
  return useCan('settings.recognition');
}

export function Can({
  permission,
  anyOf,
  children,
  fallback = null,
}: {
  permission?: PermissionKey;
  anyOf?: readonly PermissionKey[];
  children: ReactNode;
  fallback?: ReactNode;
}) {
  const { granted } = useAccess();
  const allowed = (permission ? granted.has(permission) : true) && (anyOf ? anyOf.some(p => granted.has(p)) : true);
  return <>{allowed ? children : fallback}</>;
}
