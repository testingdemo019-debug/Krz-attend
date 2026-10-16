/** Step 1, "Who are you enrolling?": the queue, the search over everyone, and the chosen person's state. */
import {
  useEnrolmentCandidate,
  useEnrolmentQueue,
  useEnrolmentSearch,
  type Candidate,
  type QueueRow,
} from '@/api/enrolment';
import { ConsentChip, FaceStatusChip } from '@/components/domain/Chips';
import { EmptyState } from '@/components/feedback/EmptyState';
import { Icon } from '@/components/icons/Icon';
import { Avatar } from '@/components/ui/Avatar';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { fmtTime } from '@/lib/format';
import { WizardNav } from './WizardNav';
import '@/components/data/DataTable.module.css';
import '@/components/domain/CropImage.module.css';

const firstName = (name: string) => name.split(' ')[0] ?? name;

export function lockedNow(lockedUntil: string | null | undefined): string | null {
  return lockedUntil && Date.parse(lockedUntil) > Date.now() ? lockedUntil : null;
}

/** The person bar above every later step. */
export function PersonBar({ candidate }: { candidate: Candidate }) {
  const p = candidate.employee;
  return (
    <div
      className="row gap-12 mb-16"
      style={{
        padding: '10px 12px',
        border: '1px solid var(--border)',
        borderRadius: 10,
        background: 'var(--surface-2)',
      }}
    >
      <Avatar id={p.id} name={p.name} />
      <div className="grow">
        <b>{p.name}</b> <span className="muted small">{p.employeeId ?? 'No Employee ID'}</span>
        <br />
        <span className="muted small">
          {p.department ?? '—'} · {p.designation ?? '—'}
        </span>
      </div>
      <FaceStatusChip status={candidate.faceStatus} />
    </div>
  );
}

function PersonRow({ row, selected, onPick }: { row: QueueRow; selected: boolean; onPick: () => void }) {
  const p = row.employee;
  const lock = lockedNow(row.lockedUntil);
  return (
    <button
      type="button"
      className={`acct ${selected ? 'selected' : ''} ${row.accountEnabled ? '' : 'disabled'}`}
      disabled={!row.accountEnabled}
      style={selected ? { borderColor: 'var(--accent)', background: 'var(--accent-weak)' } : undefined}
      onClick={onPick}
    >
      <Avatar id={p.id} name={p.name} />
      <span className="grow">
        <b>{p.name}</b> <span className="muted small">{p.employeeId ?? 'No Employee ID'}</span>
        <br />
        <span className="muted small">
          {p.department ?? '—'} · {p.designation ?? '—'}
          {p.location && !p.locationHasCamera ? ` · ${p.location}` : ''}
        </span>
      </span>
      {!row.accountEnabled ? (
        <Chip tone="red">Account disabled</Chip>
      ) : lock ? (
        <Chip tone="red" icon="lock">
          Locked until {fmtTime(lock)}
        </Chip>
      ) : (
        <FaceStatusChip status={row.faceStatus} />
      )}
    </button>
  );
}

/** The prototype's enrolExistingHTML: lockout, no camera at the location, and what is on record. */
function Existing({ candidate: c, cameraLocations }: { candidate: Candidate; cameraLocations: readonly string[] }) {
  const first = firstName(c.employee.name);
  const lock = lockedNow(c.lockedUntil);
  return (
    <>
      {lock && (
        <Callout tone="danger" icon="lock" className="mb-12">
          Enrolment is locked for {first} until {fmtTime(lock)} after repeated liveness failures. Ask a System
          Administrator to unlock it.
        </Callout>
      )}
      {!c.employee.locationHasCamera && (
        <Callout tone="warn" icon="alert" className="mb-12 small">
          {c.employee.location ?? 'Their location'} has no camera. You can enrol {first} for visits to{' '}
          {cameraLocations.length === 1 ? cameraLocations[0] : 'a location with a camera'}, but attendance stays{' '}
          <b>Not tracked</b> until their location has a camera.
        </Callout>
      )}
      <KeyValueList
        pairs={[
          ['Microsoft account', c.accountEnabled ? 'Enabled' : 'Disabled'],
          ['Face status', <FaceStatusChip key="f" status={c.faceStatus} />],
          ['Consent', <ConsentChip key="c" status={c.consent.status} />],
          ['Attendance tracking', c.trackingLabel],
          [
            'Existing templates',
            c.templates.length > 0 ? (
              <div className="row gap-4 wrap">
                {c.templates.map(t => (
                  <span key={t.id} title={`${t.pose ?? 'Upload'} · Q ${String(t.quality ?? '—')}`}>
                    {t.thumbnailUrl ? (
                      <span className="crop">
                        <img src={t.thumbnailUrl} alt="" />
                      </span>
                    ) : (
                      <span className="crop blurred" aria-label={`${c.employee.name} template`} />
                    )}
                  </span>
                ))}
              </div>
            ) : (
              'None'
            ),
          ],
        ]}
      />
    </>
  );
}

export function SelectStep({
  employeeId,
  q,
  onSearch,
  onPick,
  onNext,
}: {
  employeeId: string | null;
  q: string;
  onSearch: (q: string) => void;
  onPick: (employeeId: string) => void;
  onNext: () => void;
}) {
  const queueData = useEnrolmentQueue().data;
  const queue = queueData?.people ?? [];
  const cameraLocations = queueData?.cameraLocations ?? [];
  const search = useEnrolmentSearch(q);
  const candidate = useEnrolmentCandidate(employeeId).data;
  const searching = q.trim() !== '';
  const list = searching ? (search.data ?? []) : queue;
  const header = searching
    ? `${String(list.length)} match${list.length === 1 ? '' : 'es'}`
    : `Waiting for enrolment (${String(queue.length)})`;
  return (
    <>
      <h2 className="mb-4">Who are you enrolling?</h2>
      <p className="muted small mb-16">
        Employees come from Microsoft Entra ID.{' '}
        {cameraLocations.length === 1
          ? `The list shows ${cameraLocations[0] ?? ''} staff waiting for enrolment — the only location with a camera.`
          : 'The list shows staff waiting for enrolment at the locations with a camera.'}{' '}
        Search to find anyone else.
      </p>
      <div className="dt-search" style={{ width: '100%' }}>
        <Icon name="search" size="sm" />
        <input
          className="input"
          id="wz-q"
          placeholder="Search by name or Employee ID"
          aria-label="Search employees"
          value={q}
          onChange={event => {
            onSearch(event.target.value);
          }}
        />
      </div>
      <div className="mt-12">
        <div className="label mb-8">{header}</div>
        <div className="col gap-4" style={{ maxHeight: 340, overflow: 'auto' }}>
          {list.length > 0 ? (
            list.map(row => (
              <PersonRow
                key={row.employee.id}
                row={row}
                selected={row.employee.id === employeeId}
                onPick={() => {
                  onPick(row.employee.id);
                }}
              />
            ))
          ) : (
            <EmptyState icon="search" title="No one found" />
          )}
        </div>
      </div>
      {candidate && (
        <>
          <div className="divider" />
          <h3 className="mb-8">Selected</h3>
          <PersonBar candidate={candidate} />
          <Existing candidate={candidate} cameraLocations={cameraLocations} />
        </>
      )}
      <WizardNav
        next="Continue"
        disabled={!candidate || !candidate.accountEnabled || lockedNow(candidate.lockedUntil) !== null}
        onNext={onNext}
      />
    </>
  );
}
