/**
 * Settings → Privacy & retention: how long data is kept (saved with the page), the consent notice versions (publish a
 * new one) and the requests from employees about their data (record one, then Start → Mark completed). Erasure itself
 * is done from the employee's profile.
 */
import { useState } from 'react';
import {
  useMovePrivacyRequest,
  useNotices,
  usePrivacy,
  usePrivacyRequests,
  usePublishNotice,
  useRecordPrivacyRequest,
  useSavePrivacy,
  type PrivacyRequest,
  type PrivacyView,
} from '@/api/settings';
import type { EmployeeListItem } from '@/api/employees';
import { ErrorState } from '@/components/feedback/EmptyState';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { DateField } from '@/components/form/DateField';
import { EmployeePicker } from '@/components/form/EmployeePicker';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { fmtDate, todayDay } from '@/lib/format';
import { useDraft } from '@/lib/useDraft';
import { PRIVACY_REQUEST_STATUS, PRIVACY_REQUEST_TYPES, term } from '@/lib/vocabulary';
import { SettingField, SettingsCard } from './SettingsCard';

const TITLE = 'Privacy & retention';
const SUB = 'How long data is kept, the consent notice employees see, and requests from employees about their data.';
const MIN_NOTICE_LENGTH = 80;

type Retention = PrivacyView['settings'];

export function PrivacySettings() {
  const privacy = usePrivacy();
  if (privacy.isError) return <ErrorState what="the settings" onRetry={() => void privacy.refetch()} />;
  if (!privacy.data) {
    return (
      <SettingsCard title={TITLE} sub={SUB}>
        <SkeletonRows rows={6} columns={2} />
      </SettingsCard>
    );
  }
  return (
    <PrivacyForm
      view={privacy.data}
      reload={() => {
        void privacy.refetch();
      }}
    />
  );
}

function PrivacyForm({ view, reload }: { view: PrivacyView; reload: () => void }) {
  const save = useSavePrivacy();
  const draft = useDraft({
    title: TITLE,
    source: view.settings,
    save: d => save.mutateAsync({ ...d, version: view.version }),
    onStale: reload,
  });
  const d = draft.value;
  const retention = (key: keyof Retention, label: string, options: readonly number[], unit: string, help?: string) => (
    <SettingField label={label} htmlFor={`pv-${key}`} {...(help ? { help } : {})}>
      <select
        id={`pv-${key}`}
        className="select"
        value={d[key]}
        onChange={e => {
          draft.set({ ...d, [key]: Number(e.target.value) });
        }}
      >
        {[...new Set([...options, d[key]])]
          .sort((a, b) => a - b)
          .map(n => (
            <option key={n} value={n}>
              {n} {unit}
            </option>
          ))}
      </select>
    </SettingField>
  );
  return (
    <SettingsCard title={TITLE} sub={SUB} draft={draft}>
      <div className="set-section">
        <h3>Retention</h3>
        <div className="form-grid mt-8">
          {retention(
            'cropDays',
            'Entrance crops of employees',
            [7, 15, 30, 60],
            'days',
            'Face and back-view crops linked to a crossing.',
          )}
          {retention(
            'unknownHours',
            'Crops of unknown faces',
            [12, 24, 48, 72],
            'hours',
            'Unresolved review items expire after this.',
          )}
          {retention(
            'eventDays',
            'Crossings (without images)',
            [90, 180, 365, 730],
            'days',
            'Attendance punches in KRZ HRMS follow HRMS retention.',
          )}
          {retention('auditDays', 'Audit log', [365, 730, 1095], 'days')}
        </div>
        <p className="help mt-8">Same-day body features for Re-ID are always deleted at the end of the day.</p>
      </div>
      <Notices />
      <Requests />
    </SettingsCard>
  );
}

function Notices() {
  const notices = useNotices().data;
  const [viewing, setViewing] = useState<number | null>(null);
  const [publishing, setPublishing] = useState(false);
  const active = notices?.notices.find(n => n.active);
  const shown = notices?.notices.find(n => n.version === viewing);
  return (
    <div className="set-section">
      <div className="row between">
        <h3>Consent notice</h3>
        <Button
          size="sm"
          icon="plus"
          disabled={!active}
          onClick={() => {
            setPublishing(true);
          }}
        >
          Publish new version
        </Button>
      </div>
      <ScrollArea className="dt-wrap mt-8" label="Consent notice versions">
        <table className="dt compact">
          <thead>
            <tr>
              <th>Version</th>
              <th>Published</th>
              <th>By</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {(notices?.notices ?? []).map(n => (
              <tr key={n.version}>
                <td>v{n.version}</td>
                <td>{fmtDate(n.publishedAt)}</td>
                <td>{n.publishedBy}</td>
                <td>{n.active ? <Chip tone="green">Active</Chip> : <Chip tone="grey">Superseded</Chip>}</td>
                <td>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      setViewing(n.version);
                    }}
                  >
                    View
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollArea>
      <p className="help mt-8">
        {notices?.consentedToOlderVersion ?? 0} employees consented to an older version. They are asked again at their
        next enrolment.
      </p>
      {shown && (
        <Modal
          open
          size="lg"
          title={`Consent notice v${String(shown.version)}`}
          sub={`Published ${fmtDate(shown.publishedAt)} by ${shown.publishedBy}`}
          onClose={() => {
            setViewing(null);
          }}
        >
          <div style={{ whiteSpace: 'pre-wrap', fontSize: 13.5, lineHeight: 1.6 }}>{shown.text}</div>
        </Modal>
      )}
      {publishing && active && (
        <PublishNotice
          version={active.version}
          text={active.text}
          onClose={() => {
            setPublishing(false);
          }}
        />
      )}
    </div>
  );
}

function PublishNotice({ version, text, onClose }: { version: number; text: string; onClose: () => void }) {
  const publish = usePublishNotice();
  const [value, setValue] = useState(text);
  const next = version + 1;
  const unchanged = value.trim() === text.trim() || value.trim().length < MIN_NOTICE_LENGTH;
  return (
    <Modal
      open
      size="lg"
      title={`Publish notice v${String(next)}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={unchanged}
            pending={publish.isPending}
            onClick={() => {
              publish.mutate(value.trim(), {
                onSuccess: () => {
                  onClose();
                  toast(`Notice v${String(next)} published`, { type: 'success', logged: true });
                },
                onError: error => toast(error.message, { type: 'error' }),
              });
            }}
          >
            Publish
          </Button>
        </>
      }
    >
      <div className="field">
        <label htmlFor="nt-text">Notice text</label>
        <textarea
          id="nt-text"
          className="textarea"
          rows={14}
          value={value}
          onChange={e => {
            setValue(e.target.value);
          }}
        />
        <div className="help">Employees who consented to v{version} keep their consent until their next enrolment.</div>
      </div>
    </Modal>
  );
}

function Requests() {
  const requests = usePrivacyRequests().data ?? [];
  const move = useMovePrivacyRequest();
  const [picking, setPicking] = useState(false);
  const [recording, setRecording] = useState<EmployeeListItem | null>(null);
  const advance = (r: PrivacyRequest) => {
    const status = r.status === 'OPEN' ? 'IN_PROGRESS' : 'COMPLETED';
    const label = PRIVACY_REQUEST_STATUS[status].label;
    move.mutate(
      { code: r.code, status, outcome: null, version: r.version },
      {
        onSuccess: () => toast(`${r.code} is now ${label.toLowerCase()}`, { type: 'success', logged: true }),
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };
  return (
    <div className="set-section">
      <div className="row between">
        <h3>Requests from employees</h3>
        <Button
          size="sm"
          icon="plus"
          style={{ margin: '-3px 0' }}
          onClick={() => {
            setPicking(true);
          }}
        >
          Record a request
        </Button>
      </div>
      <ScrollArea className="dt-wrap mt-8" label="Privacy requests">
        <table className="dt compact">
          <thead>
            <tr>
              <th>Request</th>
              <th>Employee</th>
              <th>Type</th>
              <th>Received</th>
              <th>Due</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {requests.map(r => {
              const status = term(PRIVACY_REQUEST_STATUS, r.status);
              const open = r.status === 'OPEN' || r.status === 'IN_PROGRESS';
              return (
                <tr key={r.code}>
                  <td className="mono">{r.code}</td>
                  <td>
                    {r.employee.name}
                    <div className="muted xs">{r.note ?? ''}</div>
                  </td>
                  <td>{term(PRIVACY_REQUEST_TYPES, r.type) ?? r.type}</td>
                  <td className="nowrap">{fmtDate(r.receivedOn)}</td>
                  <td className="nowrap">
                    {fmtDate(r.dueOn)}
                    {r.overdue && (
                      <div className="xs" style={{ color: 'var(--red)' }}>
                        Overdue
                      </div>
                    )}
                  </td>
                  <td>
                    <Chip tone={status?.tone ?? 'grey'}>{status?.label ?? r.status}</Chip>
                  </td>
                  <td>
                    {open && (
                      <Button
                        size="sm"
                        onClick={() => {
                          advance(r);
                        }}
                      >
                        {r.status === 'OPEN' ? 'Start' : 'Mark completed'}
                      </Button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </ScrollArea>
      <p className="help mt-8">
        Erasure is done from the employee’s profile (Erase biometric data). Reply within 30 days.
      </p>
      <EmployeePicker
        open={picking}
        title="Record a request"
        sub="Who is asking about their data?"
        onClose={() => {
          setPicking(false);
        }}
        onPick={e => {
          setPicking(false);
          setRecording(e);
        }}
      />
      {recording && (
        <RecordRequest
          employee={recording}
          onClose={() => {
            setRecording(null);
          }}
        />
      )}
    </div>
  );
}

function RecordRequest({ employee, onClose }: { employee: EmployeeListItem; onClose: () => void }) {
  const record = useRecordPrivacyRequest();
  const today = todayDay();
  const [type, setType] = useState<keyof typeof PRIVACY_REQUEST_TYPES>('ACCESS');
  const [receivedOn, setReceivedOn] = useState(today);
  const [note, setNote] = useState('');
  return (
    <Modal
      open
      title={`Request from ${employee.name}`}
      sub="Due 30 days after it was received."
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            pending={record.isPending}
            onClick={() => {
              record.mutate(
                { employeeId: employee.id, type, receivedOn, note: note.trim() || null },
                {
                  onSuccess: saved => {
                    onClose();
                    toast(`${saved.code} recorded · due ${fmtDate(saved.dueOn)}`, { type: 'success', logged: true });
                  },
                  onError: error => toast(error.message, { type: 'error' }),
                },
              );
            }}
          >
            Record request
          </Button>
        </>
      }
    >
      <div className="form-grid">
        <SettingField label="Type" htmlFor="pr-type">
          <select
            id="pr-type"
            className="select"
            value={type}
            onChange={e => {
              setType(e.target.value as keyof typeof PRIVACY_REQUEST_TYPES);
            }}
          >
            {Object.entries(PRIVACY_REQUEST_TYPES).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </SettingField>
        <SettingField label="Received on" htmlFor="pr-received">
          <DateField id="pr-received" label="Received on" value={receivedOn} max={today} onChange={setReceivedOn} />
        </SettingField>
        <SettingField label="Note" htmlFor="pr-note" full>
          <textarea
            id="pr-note"
            className="textarea"
            maxLength={1000}
            value={note}
            onChange={e => {
              setNote(e.target.value);
            }}
          />
        </SettingField>
      </div>
    </Modal>
  );
}
