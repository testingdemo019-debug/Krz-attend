/**
 * Step 5, review captures and the duplicate check (the prototype's reviewHTML): the good captures with their quality,
 * Retake or Remove, and the server's lookalikes, which need the operator's confirmation before saving.
 */
import {
  useDuplicateCheck,
  useRemoveCapture,
  type Candidate,
  type Capture,
  type EnrolmentRules,
} from '@/api/enrolment';
import { EmptyState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { Spinner } from '@/components/ui/Spinner';
import { useCan } from '@/lib/permissions';
import { cx } from '@/lib/cx';
import { CAPTURE_POSES, type PoseKey } from './guidance';
import { PersonBar } from './SelectStep';
import { WizardNav } from './WizardNav';
import '@/components/domain/CropImage.module.css';
import './Enrolment.module.css';

export function ReviewStep({
  candidate,
  code,
  rules,
  captures,
  images,
  names,
  poses,
  live,
  dupConfirm,
  onDupConfirm,
  onRemoved,
  onRetake,
  onBack,
  onNext,
}: {
  candidate: Candidate;
  code: string;
  rules: EnrolmentRules;
  /** The accepted captures. */
  captures: readonly Capture[];
  images: Record<number, string>;
  names: Record<number, string>;
  poses: Partial<Record<PoseKey, number>>;
  live: boolean;
  dupConfirm: boolean;
  onDupConfirm: (value: boolean) => void;
  onRemoved: (captureId: number) => void;
  onRetake: (captureId: number) => void;
  onBack: () => void;
  onNext: () => void;
}) {
  const sensitive = useCan('people.view.sensitive');
  const remove = useRemoveCapture();
  const check = useDuplicateCheck(
    code,
    captures.map(c => c.id),
  );
  const dup = check.data?.lookalikes[0];
  const others = (check.data?.lookalikes.length ?? 1) - 1;
  const good = captures.length;
  const p = candidate.employee;
  const poseOf = (id: number) => CAPTURE_POSES.find(x => poses[x.key] === id);
  const ready = good >= rules.minGood && check.isSuccess && (!dup || dupConfirm);
  const firstImage = captures[0] ? images[captures[0].id] : undefined;
  return (
    <>
      <PersonBar candidate={candidate} />
      <h2 className="mb-4">Review captures</h2>
      <p className="muted small mb-12">
        At least <b>{rules.minGood}</b> good images are needed (quality ≥ {rules.minQuality}, eye distance ≥{' '}
        {rules.minEyePx} px). You have{' '}
        <b style={{ color: `var(--${good >= rules.minGood ? 'green' : 'red'})` }}>{good}</b>.
      </p>
      <div className="cap-grid">
        {captures.length > 0 ? (
          captures.map(c => {
            const pose = poseOf(c.id);
            const label = live ? (pose?.label ?? 'Capture') : (names[c.id] ?? 'Photo');
            const yaw = Math.round(c.quality?.yaw ?? 0);
            return (
              <div key={c.id} className="cap">
                <div className="img">
                  {images[c.id] ? <img src={images[c.id]} alt={label} /> : null}
                  <Chip tone="green" className="qs">
                    Q {c.quality?.score ?? '—'}
                  </Chip>
                </div>
                <div className="meta">
                  <b>{label}</b>
                  <span className="muted">
                    Eye distance {c.quality?.eyePx ?? '—'} px · pose {yaw > 0 ? '+' : ''}
                    {yaw}° · sharpness {c.quality?.sharp ?? '—'}
                  </span>
                  <div className="row gap-4 mt-4">
                    {live && (
                      <Button
                        size="sm"
                        icon="refresh"
                        onClick={() => {
                          onRetake(c.id);
                        }}
                      >
                        Retake
                      </Button>
                    )}
                    <Button
                      size="sm"
                      icon="trash"
                      onClick={() => {
                        remove.mutate(
                          { code, captureId: c.id },
                          {
                            onSuccess: () => {
                              onRemoved(c.id);
                            },
                            onError: error => toast(error.message, { type: 'error' }),
                          },
                        );
                      }}
                    >
                      Remove
                    </Button>
                  </div>
                </div>
              </div>
            );
          })
        ) : (
          <EmptyState icon="image" title="No captures yet" />
        )}
      </div>
      <div className="divider" />
      <h3 className="mb-8">Duplicate check</h3>
      {check.isPending && captures.length > 0 ? (
        <p className="muted small row gap-8">
          <Spinner size="sm" dark />
          Comparing with other employees…
        </p>
      ) : check.isError ? (
        <Callout tone="danger" icon="alertCircle">
          The duplicate check didn’t run: {check.error.message}{' '}
          <Button size="sm" onClick={() => void check.refetch()}>
            Try again
          </Button>
        </Callout>
      ) : dup ? (
        <Callout tone="warn" icon="alert">
          <div className="grow">
            <b>
              Looks similar to {dup.name} ({dup.employeeIdNumber ?? 'no Employee ID'})
            </b>{' '}
            — confirm these are different people before saving.
            {others > 0 && ` ${String(others)} other${others === 1 ? '' : 's'} also look similar.`}
            <div className="pair mt-12" style={{ maxWidth: 360 }}>
              <figure>
                <span className={cx('crop', 'lg', !sensitive && 'blurred')}>
                  {firstImage && <img src={firstImage} alt="" />}
                </span>
                <figcaption>{p.name} · new capture</figcaption>
              </figure>
              <figure>
                <span className={cx('crop', 'lg', !dup.thumbnailUrl && 'blurred')}>
                  {dup.thumbnailUrl && <img src={dup.thumbnailUrl} alt="" />}
                </span>
                <figcaption>
                  {dup.name} · {dup.department ?? '—'}
                </figcaption>
              </figure>
            </div>
            <label className="check mt-12">
              <input
                type="checkbox"
                checked={dupConfirm}
                onChange={event => {
                  onDupConfirm(event.target.checked);
                }}
              />
              <span>
                I confirm {p.name} and {dup.name} are different people
              </span>
            </label>
          </div>
        </Callout>
      ) : (
        check.data && (
          <Callout tone="ok" icon="checkCircle">
            No similar face found among other employees (threshold {check.data.threshold}).
          </Callout>
        )
      )}
      <WizardNav onBack={onBack} next="Continue" disabled={!ready} onNext={onNext} />
    </>
  );
}
