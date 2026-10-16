/**
 * Settings → Notifications: who is told about what. Each rule: in-app and e-mail (either on = the rule is on), push
 * (shown, off: the mobile app is not available yet), the threshold, and in one dialog the recipients (roles or
 * individual people with access) and the delivery (immediately or a daily digest, with optional quiet hours). Saving
 * sends each changed rule with its own version; "Send test e-mail" mails the signed-in user.
 */
import { useState } from 'react';
import {
  useNotificationSettings,
  useSaveNotificationRules,
  useTestEmail,
  type NotificationRule,
  type NotificationSettings,
  type RuleChange,
} from '@/api/settings';
import { ErrorState } from '@/components/feedback/EmptyState';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { MultiSelect } from '@/components/form/MultiSelect';
import { Switch } from '@/components/form/Switch';
import { TimeField } from '@/components/form/TimeField';
import { Button } from '@/components/ui/Button';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { fmtHm } from '@/lib/format';
import { useDraft } from '@/lib/useDraft';
import { SettingField, SettingsCard } from './SettingsCard';

const TITLE = 'Notifications';
const SUB =
  'Who is told about what. Recipients are roles or individual users. In-app notifications appear under the bell; e-mail goes to their work address.';

/** A rule as the page edits it: on when in-app or e-mail is on. */
interface Rule {
  code: string;
  label: string;
  threshold: number | null;
  unit: string;
  inApp: boolean;
  email: boolean;
  quietStart: string | null;
  quietEnd: string | null;
  digest: 'NONE' | 'DAILY';
  digestTime: string | null;
  roleIds: string[];
  userIds: string[];
  version: number;
}

type Delivery = Pick<Rule, 'roleIds' | 'userIds' | 'digest' | 'digestTime' | 'quietStart' | 'quietEnd'>;

const hm = (time: string | null): string | null => (time ? time.slice(0, 5) : null);

function toRule(r: NotificationRule): Rule {
  return {
    code: r.code,
    label: r.label,
    threshold: r.threshold,
    unit: r.thresholdUnit === 'DAYS' ? 'days' : 'min',
    inApp: r.enabled && r.inApp,
    email: r.enabled && r.email,
    quietStart: hm(r.quietStart),
    quietEnd: hm(r.quietEnd),
    digest: r.digest === 'DAILY' ? 'DAILY' : 'NONE',
    digestTime: hm(r.digestTime),
    roleIds: r.roles.map(x => x.id),
    userIds: r.users.map(x => x.id),
    version: r.version,
  };
}

function toChange(r: Rule): RuleChange {
  return {
    enabled: r.inApp || r.email,
    threshold: r.threshold,
    inApp: r.inApp,
    email: r.email,
    push: false,
    quietStart: r.quietStart,
    quietEnd: r.quietEnd,
    digest: r.digest,
    digestTime: r.digest === 'DAILY' ? r.digestTime : null,
    params: null,
    roleIds: r.roleIds,
    userIds: r.userIds,
    version: r.version,
  };
}

/** "Daily digest at 9:00 AM · quiet 10:00 PM–7:00 AM", shown only when the delivery is not the default. */
function deliveryText(r: Rule): string {
  return [
    r.digest === 'DAILY' ? `Daily digest at ${fmtHm(r.digestTime ?? '09:00')}` : 'Immediately',
    r.quietStart && r.quietEnd ? `quiet ${fmtHm(r.quietStart)}–${fmtHm(r.quietEnd)}` : null,
  ]
    .filter(Boolean)
    .join(' · ');
}

export function NotificationsSettings() {
  const view = useNotificationSettings();
  if (view.isError) return <ErrorState what="the settings" onRetry={() => void view.refetch()} />;
  if (!view.data) {
    return (
      <SettingsCard title={TITLE} sub={SUB}>
        <SkeletonRows rows={8} columns={4} />
      </SettingsCard>
    );
  }
  return (
    <NotificationsForm
      view={view.data}
      reload={() => {
        void view.refetch();
      }}
    />
  );
}

function NotificationsForm({ view, reload }: { view: NotificationSettings; reload: () => void }) {
  const save = useSaveNotificationRules();
  const testEmail = useTestEmail();
  const source = view.rules.map(toRule);
  const draft = useDraft({
    title: TITLE,
    source,
    save: rules =>
      save.mutateAsync(
        rules
          .filter((r, i) => JSON.stringify(r) !== JSON.stringify(source[i]))
          .map(r => ({ code: r.code, change: toChange(r) })),
      ),
    validate: rules =>
      rules.some(r => (r.inApp || r.email) && !r.roleIds.length && !r.userIds.length)
        ? 'Every enabled notification needs at least one recipient.'
        : null,
    onStale: reload,
  });
  const [editing, setEditing] = useState<number | null>(null);
  const rules = draft.value;
  const update = (i: number, patch: Partial<Rule>) => {
    draft.set(current => current.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  };
  const roleName = (id: string) => view.availableRoles.find(r => r.id === id)?.name ?? id;
  const userName = (id: string) => view.availableUsers.find(u => u.id === id)?.name ?? id;
  const sendTest = (
    <Button
      size="sm"
      variant="ghost"
      icon="mail"
      disabled={!view.mailEnabled}
      pending={testEmail.isPending}
      {...(view.mailEnabled ? {} : { tooltip: 'E-mail is turned off on the server' })}
      onClick={() => {
        testEmail.mutate('me', {
          onSuccess: () => toast('Test e-mail sent to your work address', { type: 'success', logged: true }),
          onError: error => toast(error.message, { type: 'error' }),
        });
      }}
    >
      Send test e-mail
    </Button>
  );

  return (
    <SettingsCard title={TITLE} sub={SUB} draft={draft} barStart={sendTest}>
      <ScrollArea className="dt-wrap" label="Notification rules">
        <table className="dt">
          <thead>
            <tr>
              <th>When</th>
              <th>In app</th>
              <th>E-mail</th>
              <th>Push</th>
              <th>Recipients</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r, i) => (
              <tr key={r.code}>
                <td>
                  <b>{r.label}</b>
                  {r.threshold !== null && (
                    <>
                      {' '}
                      <span className="row gap-4 mt-4" style={{ display: 'inline-flex' }}>
                        <input
                          type="number"
                          className="input"
                          style={{ width: 72, height: 30 }}
                          min={1}
                          max={240}
                          value={r.threshold}
                          aria-label={`Threshold for ${r.label}`}
                          onChange={e => {
                            update(i, { threshold: Number(e.target.value) });
                          }}
                        />{' '}
                        {r.unit}
                      </span>
                    </>
                  )}
                </td>
                <td>
                  <Switch
                    checked={r.inApp}
                    aria-label={`In-app: ${r.label}`}
                    onChange={e => {
                      update(i, { inApp: e.target.checked });
                    }}
                  />
                </td>
                <td>
                  <Switch
                    checked={r.email}
                    disabled={!view.mailEnabled}
                    aria-label={`E-mail: ${r.label}`}
                    {...(view.mailEnabled ? {} : { tooltip: 'E-mail is turned off on the server' })}
                    onChange={e => {
                      update(i, { email: e.target.checked });
                    }}
                  />
                </td>
                <td>
                  <Switch checked={false} disabled aria-label={`Push: ${r.label}`} tooltip={view.pushNote} />
                </td>
                <td>
                  <div className="row wrap gap-4">
                    {r.roleIds.map(id => (
                      <span key={id} className="role-badge">
                        {roleName(id)}
                      </span>
                    ))}
                    {r.userIds.map(id => (
                      <span key={id} className="tag">
                        {userName(id)}
                      </span>
                    ))}
                    <Button
                      size="sm"
                      variant="ghost"
                      icon="edit"
                      aria-label={`Change recipients and delivery: ${r.label}`}
                      onClick={() => {
                        setEditing(i);
                      }}
                    >
                      Change
                    </Button>
                  </div>
                  {(r.digest === 'DAILY' || (r.quietStart && r.quietEnd)) && (
                    <div className="muted xs mt-4">{deliveryText(r)}</div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollArea>
      {editing !== null && rules[editing] && (
        <RecipientsDialog
          rule={rules[editing]}
          view={view}
          onClose={() => {
            setEditing(null);
          }}
          onApply={patch => {
            update(editing, patch);
            setEditing(null);
          }}
        />
      )}
    </SettingsCard>
  );
}

/** The prototype's recipients dialog, with the rule's delivery below. */
function RecipientsDialog({
  rule,
  view,
  onClose,
  onApply,
}: {
  rule: Rule;
  view: NotificationSettings;
  onClose: () => void;
  onApply: (patch: Delivery) => void;
}) {
  const [roles, setRoles] = useState<readonly string[]>(rule.roleIds);
  const [users, setUsers] = useState<ReadonlySet<string>>(new Set(rule.userIds));
  const [digest, setDigest] = useState(rule.digest);
  const [digestTime, setDigestTime] = useState(rule.digestTime ?? '09:00');
  const [quiet, setQuiet] = useState(Boolean(rule.quietStart && rule.quietEnd));
  const [quietStart, setQuietStart] = useState(rule.quietStart ?? '22:00');
  const [quietEnd, setQuietEnd] = useState(rule.quietEnd ?? '07:00');
  return (
    <Modal
      open
      size="lg"
      title={`Recipients — ${rule.label}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            onClick={() => {
              onApply({
                roleIds: [...roles],
                userIds: [...users],
                digest,
                digestTime: digest === 'DAILY' ? digestTime : null,
                quietStart: quiet ? quietStart : null,
                quietEnd: quiet ? quietEnd : null,
              });
            }}
          >
            Apply
          </Button>
        </>
      }
    >
      <h3 className="mb-8">Roles</h3>
      <div className="row wrap gap-8 mb-16">
        {view.availableRoles.map(r => (
          <label
            key={r.id}
            className="check"
            style={{ border: '1px solid var(--border)', borderRadius: 6, padding: '6px 10px' }}
          >
            <input
              type="checkbox"
              checked={roles.includes(r.id)}
              onChange={e => {
                setRoles(current => (e.target.checked ? [...current, r.id] : current.filter(c => c !== r.id)));
              }}
            />
            {r.name}
          </label>
        ))}
      </div>
      <h3 className="mb-8">Individual users</h3>
      <MultiSelect
        items={view.availableUsers.map(u => ({
          id: u.id,
          label: u.name,
          ...(u.hasAccess ? {} : { sub: 'No access — skipped' }),
        }))}
        selected={users}
        onChange={setUsers}
        searchPlaceholder="Search users with access"
        height={220}
      />
      <h3 className="mt-16 mb-8">Delivery</h3>
      <div className="form-grid">
        <SettingField label="Send" htmlFor="nd-digest">
          <select
            id="nd-digest"
            className="select"
            value={digest}
            onChange={e => {
              setDigest(e.target.value === 'DAILY' ? 'DAILY' : 'NONE');
            }}
          >
            <option value="NONE">Immediately</option>
            <option value="DAILY">As a daily digest</option>
          </select>
        </SettingField>
        {digest === 'DAILY' && (
          <SettingField label="Digest time" help="IST">
            <TimeField label="Digest time" value={digestTime} onChange={setDigestTime} />
          </SettingField>
        )}
      </div>
      <div className="mt-12">
        <Switch
          checked={quiet}
          label="Quiet hours: e-mails wait until they end (critical alerts still go out)"
          onChange={e => {
            setQuiet(e.target.checked);
          }}
        />
      </div>
      {quiet && (
        <div className="form-grid mt-8">
          <SettingField label="From">
            <TimeField label="Quiet hours from" value={quietStart} onChange={setQuietStart} />
          </SettingField>
          <SettingField label="Until">
            <TimeField label="Quiet hours until" value={quietEnd} onChange={setQuietEnd} />
          </SettingField>
        </div>
      )}
    </Modal>
  );
}
