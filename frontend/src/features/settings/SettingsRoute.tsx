/**
 * Settings (the prototype's #/settings/:page): the "Settings" head, the sub-navigation of the pages this user may open
 * (with the pending access requests on Users & roles) and the chosen page, loaded on demand. /settings opens the first
 * allowed page.
 */
import { lazy, Suspense, type ComponentType, type LazyExoticComponent } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { usePendingAccessRequests } from '@/api/shell';
import { EmptyState } from '@/components/feedback/EmptyState';
import { Icon } from '@/components/icons/Icon';
import { Breadcrumbs } from '@/components/layout/Breadcrumbs';
import { PageHead } from '@/components/layout/PageHead';
import { ButtonLink } from '@/components/ui/Button';
import { SETTINGS_PAGES, settingsPageAllowed } from '@/lib/nav';
import { useNeedsPermissionText, usePermissionCheck } from '@/lib/permissions';
import { usePageTitle } from '@/lib/usePageTitle';
import '@/components/layout/SettingsLayout.module.css';

const BODIES: Readonly<Record<string, LazyExoticComponent<ComponentType>>> = {
  general: lazy(() => import('./GeneralSettings').then(m => ({ default: m.GeneralSettings }))),
  users: lazy(() => import('./UsersSettings').then(m => ({ default: m.UsersSettings }))),
  signin: lazy(() => import('./SignInSettings').then(m => ({ default: m.SignInSettings }))),
  cameras: lazy(() => import('./CamerasSettings').then(m => ({ default: m.CamerasSettings }))),
  recognition: lazy(() => import('./RecognitionSettings').then(m => ({ default: m.RecognitionSettings }))),
  rules: lazy(() => import('./RulesSettings').then(m => ({ default: m.RulesSettings }))),
  enrolment: lazy(() => import('./EnrolmentSettings').then(m => ({ default: m.EnrolmentSettings }))),
  notifications: lazy(() => import('./NotificationsSettings').then(m => ({ default: m.NotificationsSettings }))),
  hrms: lazy(() => import('./HrmsSettings').then(m => ({ default: m.HrmsSettings }))),
  privacy: lazy(() => import('./PrivacySettings').then(m => ({ default: m.PrivacySettings }))),
  audit: lazy(() => import('./AuditLogSettings').then(m => ({ default: m.AuditLogSettings }))),
  system: lazy(() => import('./SystemSettings').then(m => ({ default: m.SystemSettings }))),
};

/** /settings: the first page this user may open. */
export function SettingsIndex() {
  const has = usePermissionCheck();
  const first = SETTINGS_PAGES.find(p => settingsPageAllowed(p, has));
  return <Navigate to={first ? `/settings/${first.key}` : '/dashboard'} replace />;
}

export function SettingsRoute() {
  const { page: key } = useParams();
  const has = usePermissionCheck();
  const needs = useNeedsPermissionText();
  const pending = usePendingAccessRequests(has('settings.roles')).data?.length ?? 0;
  const page = SETTINGS_PAGES.find(p => p.key === key);
  usePageTitle(page ? `${page.label} · Settings` : 'Settings');
  if (!page) {
    return (
      <div className="card">
        <EmptyState
          icon="settings"
          title="Settings page not found"
          action={<ButtonLink to="/settings">All settings</ButtonLink>}
        />
      </div>
    );
  }
  const Body = BODIES[page.key] ?? null;
  return (
    <>
      <Breadcrumbs crumbs={[{ label: 'Settings', to: '/settings' }, { label: page.label }]} />
      <PageHead title="Settings" sub="Every change is written to the audit log. Each page needs its own permission." />
      <div className="settings">
        <nav className="subnav" aria-label="Settings sections">
          {SETTINGS_PAGES.filter(p => settingsPageAllowed(p, has)).map(p => (
            <Link
              key={p.key}
              to={`/settings/${p.key}`}
              className={p.key === page.key ? 'active' : undefined}
              aria-current={p.key === page.key ? 'page' : undefined}
            >
              <Icon name={p.icon} size="sm" />
              {p.label}
              {p.key === 'users' && pending > 0 && (
                <span className="badge" style={{ marginLeft: 'auto' }}>
                  {pending}
                </span>
              )}
            </Link>
          ))}
        </nav>
        <div id="set-main" style={{ minWidth: 0 }}>
          {Body && settingsPageAllowed(page, has) ? (
            <Suspense fallback={null}>
              <Body />
            </Suspense>
          ) : (
            <div className="card">
              <EmptyState
                icon="lock"
                title={`You can’t open ${page.label}`}
                text={`${page.permissions[0] ? needs(page.permissions[0]) : ''}.`}
              />
            </div>
          )}
        </div>
      </div>
    </>
  );
}
