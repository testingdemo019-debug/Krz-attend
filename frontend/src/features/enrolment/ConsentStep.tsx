/**
 * Step 2, Consent (R15): the active notice read with the person, how consent was obtained, an optional signed scan
 * and the operator's confirmation; or the record that they declined. Consent already on file for this notice can be
 * kept as it is.
 */
import type { Candidate, ConsentMethod, ConsentNotice } from '@/api/enrolment';
import { toast } from '@/components/feedback/toast';
import { FileDrop } from '@/components/form/FileDrop';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { fmtDate, fmtDateTime } from '@/lib/format';
import { useMeUser } from '@/lib/permissions';
import { CONSENT_METHODS, term } from '@/lib/vocabulary';
import { PersonBar } from './SelectStep';
import type { ConsentDraft } from './wizard';
import { WizardNav } from './WizardNav';
import '@/components/form/Field.module.css';

/** How consent can be obtained (the vocabulary's methods; "Written request" is for withdrawals). */
const GIVEN_METHODS: readonly ConsentMethod[] = ['WRITTEN_FORM', 'EMAIL_CONFIRMATION'];

export function ConsentStep({
  candidate,
  notice,
  draft,
  onDraft,
  onDecline,
  onBack,
  onNext,
  pending,
}: {
  candidate: Candidate;
  notice: ConsentNotice;
  draft: ConsentDraft;
  onDraft: (patch: Partial<ConsentDraft>) => void;
  onDecline: () => void;
  onBack: () => void;
  onNext: () => void;
  pending: boolean;
}) {
  const me = useMeUser();
  const p = candidate.employee;
  const first = p.name.split(' ')[0] ?? p.name;
  const c = candidate.consent;
  return (
    <>
      <PersonBar candidate={candidate} />
      <h2 className="mb-4">Consent</h2>
      <p className="muted small mb-12">
        Read the active notice with {first}. Written consent is required before capturing any face images.
      </p>
      {draft.onFile && (
        <Callout tone="ok" icon="checkCircle" className="mb-12">
          Consent for notice v{notice.version} is already on file ({fmtDate(c.givenOn)},{' '}
          {c.method ? (term(CONSENT_METHODS, c.method) ?? c.method) : '—'}, recorded by {c.recordedBy ?? '—'}). You can
          continue or record it again.
        </Callout>
      )}
      <div
        style={{
          maxHeight: 220,
          overflow: 'auto',
          border: '1px solid var(--border)',
          borderRadius: 10,
          padding: '12px 14px',
          background: 'var(--surface-2)',
          whiteSpace: 'pre-wrap',
          fontSize: 13,
          lineHeight: 1.6,
        }}
        // A scrollable region must be reachable by keyboard.
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
        tabIndex={0}
        role="region"
        aria-label="Notice text"
      >
        <b>Face recognition notice · version {notice.version}</b> (published {fmtDate(notice.publishedAt)}){'\n\n'}
        {notice.text}
      </div>
      <Callout tone="neutral" icon="info" className="mt-12">
        <b>Enrolment is voluntary.</b> If {first} doesn’t want to enrol, HR records their attendance in KRZ HRMS
        instead. Consent is not a condition of employment.{' '}
        <Button size="sm" className="mt-8" onClick={onDecline}>
          Record that {first} declined
        </Button>
      </Callout>
      <div className="form-grid mt-16">
        <div className="field">
          <label htmlFor="wz-cmethod">How was consent obtained?</label>
          <select
            id="wz-cmethod"
            className="select"
            value={draft.method}
            onChange={event => {
              onDraft({ method: event.target.value as ConsentMethod, onFile: false });
            }}
          >
            {GIVEN_METHODS.map(m => (
              <option key={m} value={m}>
                {CONSENT_METHODS[m]}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <span className="label">Signed scan (optional)</span>
          <FileDrop
            label="Upload signed consent scan"
            accept=".pdf,.jpg,.jpeg,application/pdf,image/jpeg"
            style={{ padding: 10 }}
            onFiles={([file]) => {
              if (!file) return;
              if (!/pdf|jpe?g/i.test(file.type || file.name)) {
                toast('Use a PDF or JPG file', { type: 'warn' });
                return;
              }
              onDraft({ document: file, onFile: false });
            }}
          >
            {draft.document ? (
              <>
                <Icon name="file" size="sm" /> <b>{draft.document.name}</b> ·{' '}
                <a
                  href="#remove"
                  onClick={event => {
                    event.preventDefault();
                    onDraft({ document: null });
                  }}
                >
                  remove
                </a>
              </>
            ) : (
              <>
                <Icon name="upload" size="sm" /> Drag a PDF or JPG here, or click to browse
              </>
            )}
          </FileDrop>
        </div>
        <label
          className="check full"
          style={{
            padding: 12,
            border: `1px solid ${draft.confirmed ? 'var(--green-line)' : 'var(--border-strong)'}`,
            borderRadius: 10,
          }}
        >
          <input
            type="checkbox"
            checked={draft.confirmed}
            onChange={event => {
              onDraft({ confirmed: event.target.checked, onFile: false });
            }}
          />
          <span>
            <b>I confirm that written consent was obtained</b> from {p.name} for notice v{notice.version}.
            <br />
            <span className="muted small">
              Recorded as {me.name} · {fmtDateTime(new Date().toISOString())}
            </span>
          </span>
        </label>
      </div>
      <WizardNav onBack={onBack} next="Continue" disabled={!draft.confirmed} pending={pending} onNext={onNext} />
    </>
  );
}
