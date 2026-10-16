/**
 * /access (F9): the outcome screens for a signed-in user without access, from /me's accessState, in the prototype's
 * wording: no role (with the access request form), request pending, account disabled, not in the directory.
 */
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { signOut, useMe, useRequestAccess, type Me } from '@/api/me';
import { queryKeys } from '@/api/queryKeys';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { fmtDateTime } from '@/lib/format';
import { usePageTitle } from '@/lib/usePageTitle';
import { BrandHeader, SignInFrame } from './SignInFrame';
import '@/components/form/Field.module.css';

export function AccessPage() {
  usePageTitle('Sign in');
  const me = useMe();
  if (me.isPending) return null;
  if (!me.data) return <Navigate to="/signin" replace />;
  if (me.data.accessState === 'ACTIVE') return <Navigate to="/dashboard" replace />;
  return <Outcome me={me.data} />;
}

function useLeave(): () => void {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return () => {
    void signOut().finally(() => {
      queryClient.setQueryData(queryKeys.me, null);
      void navigate('/signin?notice=signed_out', { replace: true });
    });
  };
}

function Outcome({ me }: { me: Me }) {
  const leave = useLeave();
  const signOutButton = (
    <Button icon="logout" iconSize="default" onClick={leave}>
      Sign out
    </Button>
  );

  if (me.accessState === 'ACCOUNT_DISABLED') {
    return (
      <SignInFrame>
        {branding => (
          <>
            <BrandHeader branding={branding} />
            <Callout tone="danger" icon="ban" className="mt-16">
              <b id="si-title">Your account has been disabled.</b>
              <br />
              Contact IT{branding?.helpdeskEmail ? ` (${branding.helpdeskEmail})` : ''}.
            </Callout>
            <div className="row mt-24">{signOutButton}</div>
          </>
        )}
      </SignInFrame>
    );
  }

  if (me.accessState === 'NOT_IN_DIRECTORY') {
    return (
      <SignInFrame wide>
        {branding => (
          <>
            <BrandHeader branding={branding} />
            <div className="empty-ic round-ic amber">
              <Icon name="userX" size="lg" />
            </div>
            <h1 id="si-title" style={{ fontSize: 20 }}>
              Your account isn’t set up in KRZ Attend yet
            </h1>
            <p className="mt-8">
              You signed in as <b>{me.name}</b> ({me.email}), but your account hasn’t been synced from Microsoft Entra
              ID. Ask IT{branding?.helpdeskEmail ? ` (${branding.helpdeskEmail})` : ''}.
            </p>
            <Callout tone="neutral" className="mt-16">
              <span className="small">
                Accounts are synced regularly from Microsoft Entra ID. New joiners appear after IT adds them to the sync
                scope.
              </span>
            </Callout>
            <div className="row mt-16">{signOutButton}</div>
          </>
        )}
      </SignInFrame>
    );
  }

  return <NoAccess me={me} signOutButton={signOutButton} />;
}

function NoAccess({ me, signOutButton }: { me: Me; signOutButton: React.ReactNode }) {
  const [note, setNote] = useState('');
  const requestAccess = useRequestAccess();
  const latest = me.accessRequest;
  const pending = latest?.status === 'PENDING' ? latest : null;
  const declined = latest?.status === 'DECLINED' ? latest : null;
  return (
    <SignInFrame wide>
      {branding => (
        <>
          <BrandHeader branding={branding} />
          <div className="empty-ic round-ic red">
            <Icon name="lock" size="lg" />
          </div>
          <h1 id="si-title" style={{ fontSize: 20 }}>
            You don’t have access to KRZ Attend yet
          </h1>
          <p className="mt-8">
            You signed in as <b>{me.name}</b> ({me.email}), but no KRZ Attend role is assigned to you. Roles are given
            by a System Administrator inside KRZ Attend.
          </p>
          {pending ? (
            <Callout icon="hourglass" className="mt-16">
              <b>Request sent {fmtDateTime(pending.createdAt)}</b> ({pending.code}). An administrator will review it.
              {pending.note && (
                <>
                  <br />
                  <span className="muted small">“{pending.note}”</span>
                </>
              )}
            </Callout>
          ) : (
            <>
              {declined && (
                <Callout tone="warn" className="mt-16">
                  Your last request ({declined.code}) was declined: {declined.reason ?? 'no reason given'}.
                </Callout>
              )}
              <div className="field mt-16">
                <label htmlFor="si-req">Why do you need access? (optional)</label>
                <textarea
                  id="si-req"
                  className="textarea"
                  rows={3}
                  maxLength={500}
                  placeholder="For example: I enrol new joiners at reception."
                  value={note}
                  onChange={e => {
                    setNote(e.target.value);
                  }}
                />
              </div>
            </>
          )}
          <div className="row mt-16 wrap">
            {!pending && (
              <Button
                variant="primary"
                icon="send"
                iconSize="default"
                pending={requestAccess.isPending}
                onClick={() => {
                  requestAccess.mutate(note.trim(), {
                    onSuccess: () => {
                      setNote('');
                      toast('Request sent to the administrators', { type: 'success', logged: true });
                    },
                    onError: error => toast(error.message, { type: 'error' }),
                  });
                }}
              >
                Request access
              </Button>
            )}
            {signOutButton}
          </div>
          <p className="muted xs mt-16">
            Your Microsoft object ID: <span className="mono">{me.identity.objectId}</span>
          </p>
        </>
      )}
    </SignInFrame>
  );
}
