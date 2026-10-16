/**
 * Step 4b, upload photos (the prototype's uploadHTML): up to 10 JPG or PNG files, each sent to the server, which
 * checks faces, size, sharpness, exposure and pose and says why a photo fails. The face crop shown is the browser's.
 */
import { useState } from 'react';
import {
  useCapture,
  useRemoveCapture,
  type Candidate,
  type CaptureResult,
  type EnrolmentRules,
  type EnrolmentSession,
} from '@/api/enrolment';
import { ApiError } from '@/api/client';
import { toast } from '@/components/feedback/toast';
import { FileDrop } from '@/components/form/FileDrop';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Skeleton } from '@/components/ui/Skeleton';
import { Spinner } from '@/components/ui/Spinner';
import { landmarker } from './landmarker';
import { PersonBar } from './SelectStep';
import { WizardNav } from './WizardNav';
import './Enrolment.module.css';

const MAX_PHOTOS = 10;

/** A photo being checked, or one the server refused before storing it. */
interface Pending {
  key: string;
  name: string;
  crop: string | null;
  error: string | null;
}

/** The prototype's checkUploadImage crop: a square around the face (levelled by the eyes), or the photo's middle. */
async function faceCrop(file: File): Promise<string | null> {
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    let box = { x0: 0.25, y0: 0.15, x1: 0.75, y1: 0.75 };
    let angle = 0;
    try {
      const lm = (await landmarker('IMAGE')).detect(img).faceLandmarks[0];
      if (lm) {
        box = lm.reduce(
          (b, p) => ({
            x0: Math.min(b.x0, p.x),
            y0: Math.min(b.y0, p.y),
            x1: Math.max(b.x1, p.x),
            y1: Math.max(b.y1, p.y),
          }),
          { x0: 1, y0: 1, x1: 0, y1: 0 },
        );
        const eL = lm[33];
        const eR = lm[263];
        if (eL && eR) {
          angle = Math.atan2((eR.y - eL.y) * img.naturalHeight, (eR.x - eL.x) * img.naturalWidth);
        }
      }
    } catch {
      // Without face guidance the photo's middle is shown; the server finds the face itself.
    }
    const c = document.createElement('canvas');
    c.width = 200;
    c.height = 200;
    const x = c.getContext('2d');
    if (!x) return null;
    const W = img.naturalWidth;
    const H = img.naturalHeight;
    const side = Math.max((box.x1 - box.x0) * W, (box.y1 - box.y0) * H) * 1.6;
    const cx = ((box.x0 + box.x1) / 2) * W;
    const cy = ((box.y0 + box.y1) / 2) * H;
    x.translate(100, 100);
    if (angle) x.rotate(-angle);
    x.drawImage(img, cx - side / 2, cy - side / 2, side, side, -100, -100, 200, 200);
    return c.toDataURL('image/jpeg', 0.85);
  } catch {
    return null;
  } finally {
    URL.revokeObjectURL(url);
  }
}

export function UploadStep({
  candidate,
  session,
  rules,
  images,
  names,
  onCaptured,
  onRemoved,
  onBack,
  onNext,
}: {
  candidate: Candidate;
  session: EnrolmentSession;
  rules: EnrolmentRules;
  images: Record<number, string>;
  names: Record<number, string>;
  onCaptured: (result: CaptureResult, image: string, name: string) => void;
  onRemoved: (captureId: number) => void;
  onBack: () => void;
  onNext: () => void;
}) {
  const capture = useCapture();
  const remove = useRemoveCapture();
  const [pending, setPending] = useState<Pending[]>([]);
  const stored = session.captures;
  const count = stored.length + pending.length;
  const good = stored.filter(c => c.accepted).length;

  const add = (files: File[]): void => {
    const room = MAX_PHOTOS - count;
    if (room <= 0) {
      toast('Up to 10 photos', { type: 'warn' });
      return;
    }
    for (const file of files.slice(0, room)) {
      if (!/image\/(jpeg|png)/.test(file.type)) {
        toast(`${file.name}: only JPG or PNG`, { type: 'warn' });
        continue;
      }
      const key = `${file.name}-${String(Math.random())}`;
      setPending(list => [...list, { key, name: file.name, crop: null, error: null }]);
      void (async () => {
        const crop = await faceCrop(file);
        setPending(list => list.map(u => (u.key === key ? { ...u, crop } : u)));
        try {
          const result = await capture.mutateAsync({ code: session.code, image: file, pose: null });
          onCaptured(result, crop ?? '', file.name);
          setPending(list => list.filter(u => u.key !== key));
        } catch (e) {
          const message =
            e instanceof ApiError && e.fieldErrors[0] ? e.fieldErrors[0].message : e instanceof Error ? e.message : '';
          setPending(list =>
            list.map(u => (u.key === key ? { ...u, error: message || 'Couldn’t read this image' } : u)),
          );
        }
      })();
    }
    if (files.length > room) {
      toast(`Only ${String(room)} more photo${room === 1 ? '' : 's'} added (limit 10)`, { type: 'warn' });
    }
  };

  return (
    <>
      <PersonBar candidate={candidate} />
      <h2 className="mb-4">Upload photos</h2>
      <p className="muted small mb-12">
        JPG or PNG, up to 10 files. Each photo needs exactly one face, at least {rules.minEyePx} px between the eyes,
        good sharpness and exposure, and a near-frontal pose.
      </p>
      <FileDrop label="Upload photos" accept="image/jpeg,image/png" multiple onFiles={add}>
        <Icon name="upload" size="xl" />
        <div className="mt-8">
          <b>Drag photos here</b> or click to browse
        </div>
        <div className="muted small mt-4">
          {count}/{MAX_PHOTOS} added
        </div>
      </FileDrop>
      <div className="cap-grid mt-16">
        {stored.map(c => {
          const reasons = c.rejectReason ? c.rejectReason.split('; ') : [];
          const name = names[c.id] ?? 'Photo';
          return (
            <div key={c.id} className={`cap ${c.accepted ? '' : 'bad'}`}>
              <div className="img">
                {images[c.id] ? <img src={images[c.id]} alt={`Aligned face crop of ${name}`} /> : null}
                <Chip tone={c.accepted ? 'green' : 'red'} icon={c.accepted ? 'check' : 'x'} className="qs">
                  {c.accepted ? 'Pass' : 'Fail'}
                </Chip>
              </div>
              <div className="meta">
                <b className="ellipsis" title={name}>
                  {name}
                </b>
                {c.accepted ? (
                  <span className="muted">
                    Q {c.quality?.score ?? '—'} · {c.quality?.eyePx ?? '—'} px · sharp {c.quality?.sharp ?? '—'}
                  </span>
                ) : (
                  reasons.map(r => (
                    <span key={r} style={{ color: 'var(--red)' }}>
                      <Icon name="alertCircle" size="sm" /> {r}
                    </span>
                  ))
                )}
                <Button
                  size="sm"
                  icon="trash"
                  className="mt-4"
                  onClick={() => {
                    remove.mutate(
                      { code: session.code, captureId: c.id },
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
          );
        })}
        {pending.map(u => (
          <div key={u.key} className={`cap ${u.error ? 'bad' : ''}`}>
            <div className="img">
              {u.crop ? (
                <img src={u.crop} alt={`Aligned face crop of ${u.name}`} />
              ) : (
                <Skeleton width="100%" height="100%" />
              )}
              {u.error && (
                <Chip tone="red" icon="x" className="qs">
                  Fail
                </Chip>
              )}
            </div>
            <div className="meta">
              <b className="ellipsis" title={u.name}>
                {u.name}
              </b>
              {u.error ? (
                <span style={{ color: 'var(--red)' }}>
                  <Icon name="alertCircle" size="sm" /> {u.error}
                </span>
              ) : (
                <span className="muted row gap-4">
                  <Spinner size="sm" dark />
                  Checking…
                </span>
              )}
              {u.error && (
                <Button
                  size="sm"
                  icon="trash"
                  className="mt-4"
                  onClick={() => {
                    setPending(list => list.filter(x => x.key !== u.key));
                  }}
                >
                  Remove
                </Button>
              )}
            </div>
          </div>
        ))}
      </div>
      <WizardNav
        onBack={onBack}
        next={`Review ${String(good)} good photos`}
        disabled={good < rules.minGood || pending.some(u => !u.error)}
        onNext={onNext}
      />
    </>
  );
}
