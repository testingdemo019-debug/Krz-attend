/**
 * Step 3, "Choose a capture method": live camera, photos, or a capture at an entry point (the attendance camera,
 * D-118), and replace or add to existing templates (a capture at an entry point always adds).
 */
import type { Candidate, EnrolmentMethod } from '@/api/enrolment';
import { Icon, type IconName } from '@/components/icons/Icon';
import { Chip } from '@/components/ui/Chip';
import { plural } from '@/lib/text';
import { PersonBar } from './SelectStep';
import { WizardNav } from './WizardNav';

const METHODS: readonly (readonly [EnrolmentMethod, IconName, string])[] = [
  ['LIVE', 'camera', 'Live camera'],
  ['UPLOAD', 'upload', 'Upload photos'],
  ['CAMERA', 'cctv', 'Capture at entry point'],
];

const MODES = [
  ['REPLACE', 'Replace all templates', 'Use for poor or old templates. Existing templates are deleted when you save.'],
  ['ADD', 'Add to existing', 'Keeps current templates and adds the new captures.'],
] as const;

export function MethodStep({
  candidate,
  method,
  mode,
  allowUpload,
  onMethod,
  onMode,
  onBack,
  onNext,
  pending,
}: {
  candidate: Candidate;
  method: EnrolmentMethod;
  mode: 'REPLACE' | 'ADD';
  allowUpload: boolean;
  onMethod: (method: EnrolmentMethod) => void;
  onMode: (mode: 'REPLACE' | 'ADD') => void;
  onBack: () => void;
  onNext: () => void;
  pending: boolean;
}) {
  const first = candidate.employee.name.split(' ')[0] ?? candidate.employee.name;
  const templates = candidate.templates.length;
  const describe = (m: EnrolmentMethod) =>
    m === 'LIVE'
      ? 'Recommended. Guided capture of 5 poses with automatic quality checks and liveness.'
      : m === 'CAMERA'
        ? 'Uses the attendance camera, so templates match its angle and light. Always added to existing templates.'
        : allowUpload
          ? 'JPG or PNG, up to 10 files. Each photo is checked automatically.'
          : 'Disabled in Settings → Enrolment & quality.';
  return (
    <>
      <PersonBar candidate={candidate} />
      <h2 className="mb-4">Choose a capture method</h2>
      <p className="muted small mb-16">Live capture gives the best templates and includes a liveness check.</p>
      <div className="grid grid-2">
        {METHODS.map(([key, icon, title]) => {
          const off = key === 'UPLOAD' && !allowUpload;
          return (
            <button
              key={key}
              type="button"
              className="card pad"
              disabled={off}
              aria-pressed={method === key}
              style={{
                textAlign: 'left',
                cursor: off ? 'not-allowed' : 'pointer',
                ...(method === key ? { borderColor: 'var(--accent)', boxShadow: '0 0 0 3px var(--accent-weak)' } : {}),
                ...(off ? { opacity: 0.5 } : {}),
              }}
              onClick={() => {
                onMethod(key);
              }}
            >
              <div className="row between">
                <span
                  className="kpi-ic"
                  style={{
                    position: 'static',
                    width: 40,
                    height: 40,
                    borderRadius: 10,
                    display: 'grid',
                    placeItems: 'center',
                    background: 'var(--accent-weak)',
                    color: 'var(--accent-text)',
                  }}
                >
                  <Icon name={icon} />
                </span>
                {key === 'LIVE' && <Chip tone="green">Recommended</Chip>}
              </div>
              <h3 className="mt-12">{title}</h3>
              <p className="muted small mt-4">{describe(key)}</p>
            </button>
          );
        })}
      </div>
      {templates > 0 && method !== 'CAMERA' && (
        <div className="mt-16">
          <div className="label mb-8">
            {first} already has {plural(templates, 'template')}
          </div>
          <div className="row gap-16 wrap">
            {MODES.map(([key, title, text]) => (
              <label
                key={key}
                className="check"
                style={{
                  padding: '10px 12px',
                  border: `1px solid ${mode === key ? 'var(--accent)' : 'var(--border)'}`,
                  borderRadius: 10,
                  maxWidth: 340,
                }}
              >
                <input
                  type="radio"
                  name="wz-mode"
                  checked={mode === key}
                  onChange={() => {
                    onMode(key);
                  }}
                />
                <span>
                  <b>{title}</b>
                  <br />
                  <span className="muted small">{text}</span>
                </span>
              </label>
            ))}
          </div>
        </div>
      )}
      <WizardNav onBack={onBack} next="Continue" pending={pending} onNext={onNext} />
    </>
  );
}
