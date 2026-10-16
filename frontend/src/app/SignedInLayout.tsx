/**
 * Everything around a signed-in page: sidebar, topbar, realtime updates, global shortcuts ("/" and "?"), the session
 * watcher, My account and the shortcuts dialog. Pages render in the Outlet.
 */
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { markUserActivity } from '@/api/client';
import { signOut, type Me, type Meta, type PermissionView } from '@/api/me';
import { queryKeys } from '@/api/queryKeys';
import { connectRealtime } from '@/api/realtime';
import { openReviewTotal, usePendingAccessRequests, useReviewCounts } from '@/api/shell';
import { ShortcutsDialog } from '@/components/feedback/ShortcutsDialog';
import { AppShell } from '@/components/layout/AppShell';
import { Sidebar } from '@/components/layout/Sidebar';
import { Topbar } from '@/components/layout/Topbar';
import { MyAccountDrawer } from '@/components/layout/topbar/MyAccountDrawer';
import { isTyping, overlayOpen } from '@/lib/keyboard';
import { navFor } from '@/lib/nav';
import { usePermissionCheck } from '@/lib/permissions';
import { readPreference, writePreference } from '@/lib/storage';
import { DetailDrawers } from './DetailDrawers';
import { SessionWatcher } from './SessionWatcher';
import type { ShellContext } from './shell';

export function SignedInLayout({ me, meta, catalogue }: { me: Me; meta: Meta; catalogue: readonly PermissionView[] }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const has = usePermissionCheck();
  const sections = useMemo(() => navFor(has), [has]);
  const [collapsed, setCollapsed] = useState(() => readPreference('sidebar-collapsed', false));
  const [account, setAccount] = useState(false);
  const [shortcuts, setShortcuts] = useState(false);
  const shell = useMemo<ShellContext>(
    () => ({
      showShortcuts: () => {
        setShortcuts(true);
      },
    }),
    [],
  );
  const searchRef = useRef<HTMLInputElement>(null);
  const reviewCount = openReviewTotal(useReviewCounts(has('review.resolve')).data);
  const pendingRequests = usePendingAccessRequests(has('settings.roles')).data?.length ?? 0;

  useEffect(() => connectRealtime(queryClient), [queryClient]);

  useEffect(() => {
    markUserActivity();
    window.scrollTo(0, 0);
  }, [location.pathname]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (isTyping(event) || overlayOpen()) return;
      if (event.key === '/') {
        event.preventDefault();
        searchRef.current?.focus();
      } else if (event.key === '?') {
        event.preventDefault();
        setShortcuts(true);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
    };
  }, []);

  const leave = useCallback(
    (notice: 'signed_out' | 'expired') => {
      const returnTo =
        notice === 'expired' ? `&returnTo=${encodeURIComponent(location.pathname + location.search)}` : '';
      queryClient.setQueryData(queryKeys.me, null);
      queryClient.removeQueries({ predicate: query => query.queryKey[0] !== 'me' && query.queryKey[0] !== 'branding' });
      void navigate(`/signin?notice=${notice}${returnTo}`, { replace: true });
    },
    [location.pathname, location.search, navigate, queryClient],
  );
  const onSignOut = useCallback(() => {
    void signOut().finally(() => {
      leave('signed_out');
    });
  }, [leave]);
  const onExpired = useCallback(() => {
    leave('expired');
  }, [leave]);

  return (
    <AppShell
      collapsed={collapsed}
      sidebar={
        <Sidebar
          sections={sections}
          orgName={meta.orgName}
          logoUrl={meta.logoUrl}
          collapsed={collapsed}
          badges={{ review: reviewCount, accessRequests: pendingRequests }}
          onToggle={() => {
            setCollapsed(c => {
              writePreference('sidebar-collapsed', !c);
              return !c;
            });
          }}
        />
      }
      topbar={
        <Topbar
          me={me}
          sections={sections}
          reviewCount={reviewCount}
          searchRef={searchRef}
          onToggleSidebar={() => {
            setCollapsed(c => {
              writePreference('sidebar-collapsed', !c);
              return !c;
            });
          }}
          onAccount={() => {
            setAccount(true);
          }}
          onShortcuts={() => {
            setShortcuts(true);
          }}
          onSignOut={onSignOut}
        />
      }
    >
      <DetailDrawers>
        <Outlet context={shell} />
      </DetailDrawers>
      <MyAccountDrawer
        me={me}
        catalogue={catalogue}
        open={account}
        onClose={() => {
          setAccount(false);
        }}
        onSignOut={onSignOut}
      />
      <ShortcutsDialog
        open={shortcuts}
        onClose={() => {
          setShortcuts(false);
        }}
      />
      <SessionWatcher session={me.session} onExpired={onExpired} onSignOut={onSignOut} />
    </AppShell>
  );
}
