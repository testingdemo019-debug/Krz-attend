/** Step 6, "Save enrolment", and the success screen with test recognition and the next person in the queue. */
import { useState } from 'react';
import {
  useEnrolmentQueue,
  type Candidate,
  type Completed,
  type ConsentNotice,
  type EnrolmentRules,
} from '@/api/enrolment';
import { Icon } from '@/components/icons/Icon';
import { Button, ButtonLink } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { fmtDateTime } from '@/lib/format';
import { useCan, useMeUser } from '@/lib/permissions';
import { CONSENT_METHODS, TRACKING_REASONS } from '@/lib/vocabulary';
import { PersonBar } from './SelectStep';
import { TestRecognitionDialog } from './TestRecognitionDialog';
import type { ConsentDraft } from './wizard';
import './Enrolment.module.css';

export function SaveStep({
  candidate,
  notice,
  rules,
  consent,
  good,
  method,
  cameraCode = null,
  mode,
  guidance,
  pending,
  onBack,
  onSave,
}: {
  candidate: Candidate;
  notice: ConsentNotice;
  rules: EnrolmentRules;
  consent: ConsentDraft;
  good: number;
  method: 'LIVE' | 'UPLOAD' | 'CAMERA';
  /** The camera of a capture at an entry point. */
  cameraCode?: string | null;
  mode: 'REPLACE' | 'ADD';
  guidance: 'mp' | 'manual' | null;
  pending: boolean;
  onBack: () => void;
  onSave: () => void;
}) {
  const me = useMeUser();
  const p = candidate.employee;
  const existing = candidate.templates.length;
  const tracking = candidate.trackingLabel;
  const followsFace = tracking === 'Tracked by camera' || tracking === `Not tracked — ${TRACKING_REASONS.NO_FACE}`;
  return (
    <>
      <PersonBar candidate={candidate} />
      <h2 className="mb-12">Save enrolment</h2>
      <KeyValueList
        pairs={[
          ['Employee', `${p.name} (${p.employeeId ?? 'no Employee ID'})`],
          [
            'Templates',
            `${String(good)} new · ${
              existing
                ? mode === 'REPLACE'
                  ? `replaces ${String(existing)} existing`
                  : `added to ${String(existing)} existing`
                : 'first enrolment'
            }`,
          ],
          [
            'Method',
            method === 'LIVE'
              ? `Live camera · ${guidance === 'mp' ? 'MediaPipe guidance' : 'manual capture'}${
                  rules.livenessRequired ? ' · liveness passed' : ''
                }`
              : method === 'CAMERA'
                ? `Capture at entry point${cameraCode ? ` · ${cameraCode}` : ''}`
                : 'Uploaded photos',
          ],
          [
            'Consent',
            `${CONSENT_METHODS[consent.method]} · notice v${String(notice.version)}${
              consent.document ? ` · ${consent.document.name}` : ''
            }`,
          ],
          ['Recorded by', `${me.name} · ${fmtDateTime(new Date().toISOString())}`],
          [
            'Attendance tracking',
            p.locationHasCamera
              ? followsFace
                ? 'Tracked by camera from the next crossing'
                : `${tracking} (unchanged)`
              : `Not tracked — ${p.location ?? 'their location'} has no camera`,
          ],
        ]}
      />
      <Callout tone="neutral" icon="info" className="mt-16">
        <div className="small">
          Saving stores face templates (numerical representations) and a thumbnail of each, encrypted. The other
          captures of this session are discarded.
        </div>
      </Callout>
      <div className="row between mt-24">
        <Button icon="arrowLeft" onClick={onBack}>
          Back
        </Button>
        <Button variant="primary" size="lg" icon="check" iconSize="default" pending={pending} onClick={onSave}>
          Save enrolment
        </Button>
      </div>
    </>
  );
}

export function EnrolSuccess({
  candidate,
  saved,
  recordedBy,
  onNext,
}: {
  candidate: Candidate;
  saved: Completed;
  recordedBy: string;
  onNext: (employeeId: string | null) => void;
}) {
  const canView = useCan('people.view');
  const queue = useEnrolmentQueue().data?.people ?? [];
  const [testing, setTesting] = useState(false);
  const p = candidate.employee;
  const next = queue.find(row => row.employee.id !== p.id);
  return (
    <div className="lock-card">
      <div
        className="empty-ic"
        style={{
          width: 64,
          height: 64,
          borderRadius: '50%',
          background: 'var(--green-bg)',
          color: 'var(--green)',
          display: 'grid',
          placeItems: 'center',
          margin: '0 auto 14px',
        }}
      >
        <Icon name="checkCircle" size="xl" />
      </div>
      <h2>{p.name} is enrolled</h2>
      <p className="muted mt-8">
        {saved.templates} new templates saved · {saved.total} in total · consent recorded by {recordedBy}.
        {saved.trackingChanged ? ' Attendance is now tracked by camera.' : ''}
      </p>
      <div className="row gap-8 mt-24" style={{ justifyContent: 'center', flexWrap: 'wrap' }}>
        <Button
          icon="scanface"
          onClick={() => {
            setTesting(true);
          }}
        >
          Test recognition
        </Button>
        {canView && <ButtonLink to={`/employees/${p.id}?tab=face`}>View profile</ButtonLink>}
        <Button
          variant="primary"
          icon="userPlus"
          onClick={() => {
            onNext(next?.employee.id ?? null);
          }}
        >
          Enrol next person
        </Button>
      </div>
      {next && (
        <p className="muted small mt-12">
          Next in queue: {next.employee.name} ({next.employee.employeeId ?? 'no Employee ID'})
        </p>
      )}
      {testing && (
        <TestRecognitionDialog
          employeeId={p.id}
          onClose={() => {
            setTesting(false);
          }}
        />
      )}
    </div>
  );
}
