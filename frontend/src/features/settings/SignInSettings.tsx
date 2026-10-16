/**
 * Settings → Microsoft sign-in: the connection facts come from the server's configuration and are read-only (the client
 * secret is never shown, not even in part); the session timeout, sync interval and erase-after days are editable.
 * "Test sign-in" asks Microsoft for a token and reads one user from Graph. The sync-scope groups are configured on the
 * server (ENTRA_SYNC_SCOPE, ENTRA_SYNC_GROUP_IDS) and shown here.
 */
import { useSaveSignIn, useSignIn, useSyncRuns, useTestSignIn, type SignInView } from '@/api/settings';
import { ErrorState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { copyText } from '@/lib/clipboard';
import { fmtDate, fmtDateTime } from '@/lib/format';
import { plural } from '@/lib/text';
import { useDraft } from '@/lib/useDraft';
import { SyncNowButton } from '@/features/employees/SyncNowButton';
import { SettingField, SettingsCard } from './SettingsCard';

const TITLE = 'Microsoft sign-in';
const SUB =
  'Single sign-on with Microsoft Entra ID, and the directory sync that brings in employee identities. Roles are managed in Users & roles, not in Entra ID.';

/** What the directory sync maps (the sync's fixed behaviour). */
const MAPPING = [
  {
    field: 'User key',
    attr: 'oid + tid',
    locked: true,
    note: 'Immutable object ID and tenant ID. Never email or UPN.',
  },
  { field: 'Display name', attr: 'displayName', locked: false, note: 'Display only.' },
  {
    field: 'Work email',
    attr: 'userPrincipalName',
    locked: false,
    note: 'Display and sign-in hint only — never used for access decisions.',
  },
  {
    field: 'Account enabled',
    attr: 'accountEnabled',
    locked: false,
    note: 'Disabled accounts lose access immediately.',
  },
  {
    field: 'Employee ID (optional)',
    attr: 'employeeId',
    locked: false,
    note: 'Off: Employee ID is managed in KRZ Attend until HRMS sync exists.',
  },
] as const;

export function SignInSettings() {
  const signIn = useSignIn();
  if (signIn.isError) return <ErrorState what="the settings" onRetry={() => void signIn.refetch()} />;
  if (!signIn.data) {
    return (
      <SettingsCard title={TITLE} sub={SUB}>
        <SkeletonRows rows={6} columns={2} />
      </SettingsCard>
    );
  }
  return (
    <SignInForm
      view={signIn.data}
      reload={() => {
        void signIn.refetch();
      }}
    />
  );
}

function SignInForm({ view, reload }: { view: SignInView; reload: () => void }) {
  const save = useSaveSignIn();
  const test = useTestSignIn();
  const runs = useSyncRuns().data ?? [];
  const draft = useDraft({
    title: TITLE,
    source: view.settings,
    save: d => save.mutateAsync({ ...d, version: view.version }),
    onStale: reload,
  });
  const d = draft.value;
  const last = runs[0];
  const select = (id: string, value: number, options: readonly number[], unit: string, set: (n: number) => void) => (
    <select
      id={id}
      className="select"
      value={value}
      onChange={e => {
        set(Number(e.target.value));
      }}
    >
      {[...new Set([...options, value])]
        .sort((a, b) => a - b)
        .map(n => (
          <option key={n} value={n}>
            {n} {unit}
          </option>
        ))}
    </select>
  );

  return (
    <SettingsCard title={TITLE} sub={SUB} draft={draft}>
      <div className="set-section">
        <div className="row between">
          <h3>Connection</h3>
          <Chip tone={view.secretSet ? 'green' : 'red'} icon={view.secretSet ? 'check' : 'x'}>
            {view.secretSet ? 'Connected' : 'Not connected'}
          </Chip>
        </div>
        <KeyValueList
          pairs={[
            [
              'Tenant ID',
              <>
                <span className="mono xs">{view.tenantId}</span>{' '}
                <Button
                  size="sm"
                  variant="ghost"
                  icon="copy"
                  aria-label="Copy tenant ID"
                  onClick={() => void copyText(view.tenantId)}
                />
              </>,
            ],
            ['Application (client) ID', <span className="mono xs">{view.clientId}</span>],
            ['Redirect URI', <span className="mono xs">{view.redirectUri}</span>],
            [
              'Client secret',
              view.secretSet ? (
                <>
                  <span className="mono">••••••••</span>{' '}
                  <span className="muted small">
                    stored on the server{view.secretExpires ? ` · expires ${fmtDate(view.secretExpires)}` : ''}
                  </span>
                </>
              ) : (
                <span className="muted small">Not set — enter it on the server</span>
              ),
            ],
            ['Allowed domain', view.allowedDomain],
            ['Last test', view.lastTestedAt ? fmtDateTime(view.lastTestedAt) : 'Never'],
          ]}
        />
        <Callout tone="neutral" icon="info" className="mt-12 small">
          <b>Who can sign in:</b> work accounts in this tenant only, and only if they have a role in KRZ Attend.
          Optionally, Entra ID can restrict sign-in further with “Assignment required” and a security group.
        </Callout>
        <div className="row wrap gap-8 mt-12">
          <Button
            size="sm"
            icon="activity"
            pending={test.isPending}
            onClick={() => {
              test.mutate(undefined, {
                onSuccess: result => {
                  if (result.ok) {
                    toast('Sign-in works — token issued and Microsoft Graph reachable', {
                      type: 'success',
                      logged: true,
                    });
                  } else {
                    toast(result.message, { type: 'error', logged: true });
                  }
                },
                onError: error => toast(error.message, { type: 'error' }),
              });
            }}
          >
            {test.isPending ? 'Testing…' : 'Test sign-in'}
          </Button>
          <a className="btn btn-sm" href="https://entra.microsoft.com/" target="_blank" rel="noreferrer">
            <Icon name="external" size="sm" />
            Open Entra admin centre
          </a>
        </div>
      </div>
      <div className="set-section">
        <h3>Sessions</h3>
        <div className="form-grid mt-8">
          <SettingField label="Sign out after inactivity" htmlFor="si-idle" help="A warning appears 1 minute before.">
            {select('si-idle', d.idleTimeoutMin, [15, 30, 60, 120], 'minutes', n => {
              draft.set({ ...d, idleTimeoutMin: n });
            })}
          </SettingField>
        </div>
      </div>
      <div className="set-section">
        <h3>Directory sync</h3>
        <p className="help">
          Identity only: name, work email, account enabled. Employee ID, department and shift are managed in KRZ Attend
          until KRZ HRMS sync is ready.
        </p>
        <div className="form-grid mt-8">
          <SettingField label="Sync every" htmlFor="si-every">
            {select('si-every', d.syncEvery, [1, 3, 6, 12, 24], 'hours', n => {
              draft.set({ ...d, syncEvery: n });
            })}
          </SettingField>
          <SettingField
            label="Erase face data after an account is disabled"
            htmlFor="si-erase"
            help="Templates are deleted automatically; attendance history is kept."
          >
            {select('si-erase', d.eraseAfterDays, [7, 14, 30, 60], 'days', n => {
              draft.set({ ...d, eraseAfterDays: n });
            })}
          </SettingField>
          <SettingField
            label="Sync-scope groups"
            full
            help={
              view.syncScope === 'ALL'
                ? 'Every member of the tenant is synced.'
                : 'Only members of these Entra ID groups are synced.'
            }
          >
            <div className="row wrap gap-8">
              {view.syncScope === 'ALL' ? (
                <span className="small">All members</span>
              ) : (
                view.syncGroups.map(g => (
                  <label
                    key={g.id}
                    className="check"
                    style={{ border: '1px solid var(--border)', borderRadius: 6, padding: '6px 10px' }}
                    title={`${g.id} · set on the server (ENTRA_SYNC_GROUP_IDS)`}
                  >
                    <input type="checkbox" checked disabled />
                    <span className="mono small">{g.name}</span>
                  </label>
                ))
              )}
            </div>
          </SettingField>
        </div>
        <h3 className="mt-16 mb-8">Attribute mapping</h3>
        <ScrollArea className="dt-wrap" label="Attribute mapping">
          <table className="dt compact">
            <thead>
              <tr>
                <th>KRZ Attend field</th>
                <th>Entra ID attribute</th>
                <th>Notes</th>
              </tr>
            </thead>
            <tbody>
              {MAPPING.map(m => (
                <tr key={m.field}>
                  <td>
                    {m.field} {m.locked && <Icon name="lock" size="sm" />}
                  </td>
                  <td className="mono small">{m.attr}</td>
                  <td className="small muted">{m.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollArea>
        <div className="row between mt-16">
          <h3>Sync history</h3>
          <SyncNowButton size="sm" />
        </div>
        <ScrollArea className="dt-wrap mt-8" label="Sync runs">
          <table className="dt compact">
            <thead>
              <tr>
                <th>Run</th>
                <th>When</th>
                <th>Result</th>
                <th className="num">Added</th>
                <th className="num">Updated</th>
                <th className="num">Disabled</th>
                <th>By</th>
              </tr>
            </thead>
            <tbody>
              {runs.map(r => (
                <tr key={r.code}>
                  <td className="mono">{r.code}</td>
                  <td className="nowrap small">{fmtDateTime(r.startedAt)}</td>
                  <td>
                    {r.status === 'SUCCESS' ? (
                      <Chip tone="green">Success</Chip>
                    ) : r.status === 'FAILED' ? (
                      <Chip tone="red" title={r.error ?? ''}>
                        Failed
                      </Chip>
                    ) : (
                      <Chip tone="amber" title={r.warnings.join(' · ')}>
                        {plural(r.warnings.length, 'warning')}
                      </Chip>
                    )}
                  </td>
                  <td className="num">{r.added}</td>
                  <td className="num">{r.updated}</td>
                  <td className="num">{r.disabled}</td>
                  <td className="small">{r.triggeredBy ?? 'Scheduled'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollArea>
        {last && last.warnings.length > 0 && (
          <Callout tone="warn" icon="alert" className="mt-8 small">
            {last.warnings.map((w, i) => (
              <span key={w}>
                {i > 0 && <br />}
                {w}
              </span>
            ))}
          </Callout>
        )}
      </div>
    </SettingsCard>
  );
}
