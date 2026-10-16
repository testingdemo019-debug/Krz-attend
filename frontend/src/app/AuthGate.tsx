/**
 * Loads /me and routes by accessState (F4): not signed in → /signin (with returnTo), no access → /access, active →
 * the app, once /meta (time zone, formats) and the permission catalogue are in.
 */
import { useLocation, Navigate } from 'react-router';
import { useMe, useMeta, usePermissionCatalogue, type Me } from '@/api/me';
import { ErrorState } from '@/components/feedback/EmptyState';
import { configureFormat } from '@/lib/format';
import { AccessProvider } from '@/lib/permissions';
import { SignedInLayout } from './SignedInLayout';

export function AuthGate() {
  const me = useMe();
  const location = useLocation();
  if (me.isPending) return null;
  if (me.isError) {
    return (
      <div className="signin">
        <ErrorState what="your account" onRetry={() => void me.refetch()} />
      </div>
    );
  }
  if (!me.data) {
    const here = location.pathname + location.search;
    return <Navigate to={here === '/' ? '/signin' : `/signin?returnTo=${encodeURIComponent(here)}`} replace />;
  }
  if (me.data.accessState !== 'ACTIVE') return <Navigate to="/access" replace />;
  return <ActiveApp me={me.data} />;
}

function ActiveApp({ me }: { me: Me }) {
  const meta = useMeta(true);
  const catalogue = usePermissionCatalogue(true);
  if (meta.isError || catalogue.isError) {
    return (
      <div className="signin">
        <ErrorState
          what="KRZ Attend"
          onRetry={() => {
            void meta.refetch();
            void catalogue.refetch();
          }}
        />
      </div>
    );
  }
  if (!meta.data || !catalogue.data) return null;
  configureFormat({ timeZone: meta.data.timezone, hour12: meta.data.timeFormat === '12h' });
  return (
    <AccessProvider me={me} catalogue={catalogue.data}>
      <SignedInLayout me={me} meta={meta.data} catalogue={catalogue.data} />
    </AccessProvider>
  );
}
