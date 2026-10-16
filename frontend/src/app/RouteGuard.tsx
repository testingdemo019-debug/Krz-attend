/**
 * The prototype's route(…, {perm}): a page the user may not open shows the lock state with the permission it needs;
 * unknown addresses show "Page not found".
 */
import type { ReactNode } from 'react';
import { EmptyState } from '@/components/feedback/EmptyState';
import '@/components/layout/Card.module.css';
import { ButtonLink } from '@/components/ui/Button';
import { useNeedsPermissionText, usePermissionCheck, type PermissionKey } from '@/lib/permissions';
import { usePageTitle, useRouteTitle } from '@/lib/usePageTitle';

export type RouteRule = PermissionKey | ((has: (p: PermissionKey) => boolean) => boolean);

export function RouteGuard({ title, rule, children }: { title: string; rule?: RouteRule; children: ReactNode }) {
  const has = usePermissionCheck();
  const needs = useNeedsPermissionText();
  const allowed = rule === undefined || (typeof rule === 'string' ? has(rule) : rule(has));
  useRouteTitle(allowed ? title : 'No access');
  if (allowed) return <>{children}</>;
  const reason =
    typeof rule === 'string' ? needs(rule) : 'Your role doesn’t include this page — ask a System Administrator';
  return <NoAccess title={title} reason={reason} />;
}

function NoAccess({ title, reason }: { title: string; reason: string }) {
  return (
    <div className="card" style={{ maxWidth: 640, margin: '40px auto' }}>
      <EmptyState
        icon="lock"
        title="You don’t have access to this page"
        text={`${title}: ${reason}.`}
        action={<ButtonLink to="/dashboard">Go to dashboard</ButtonLink>}
      />
    </div>
  );
}

export function NotFound() {
  usePageTitle('Page not found');
  return (
    <EmptyState
      icon="alertCircle"
      title="Page not found"
      text="This address doesn’t match any page in KRZ Attend."
      action={<ButtonLink to="/dashboard">Go to dashboard</ButtonLink>}
    />
  );
}
