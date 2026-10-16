import type { ReviewItem } from '@/api/review';
import { CropImage } from '@/components/domain/CropImage';
import { Modal } from '@/components/feedback/Modal';
import { Button } from '@/components/ui/Button';
import { DASH, fmtTime } from '@/lib/format';
import { useCan } from '@/lib/permissions';
import { plural } from '@/lib/text';
import { REVIEW_TYPES, term } from '@/lib/vocabulary';
import '@/features/enrolment/Enrolment.module.css';

/**
 * The prototype's review-gallery: every crop of the item with its time, direction, camera and quality; an unknown
 * face's crossing can be chosen as the best crop. Crops stay blurred unless revealed, and each reveal is logged
 * (security §4), so the sub-line says that instead of "viewing face crops is logged".
 */
export function CropGallery({
  item,
  onClose,
  onPickBest,
}: {
  item: ReviewItem;
  onClose: () => void;
  onPickBest: (code: string) => void;
}) {
  const canReveal = useCan('people.view.sensitive');
  const n = item.sightings.length;
  return (
    <Modal
      open
      size="lg"
      onClose={onClose}
      title={item.label ?? `${term(REVIEW_TYPES, item.type)?.short ?? item.typeLabel} · ${item.code}`}
      sub={`${plural(n, 'crop')}${canReveal ? ' · revealing a crop is logged' : ''}`}
    >
      <div className="cap-grid">
        {item.sightings.map(s => (
          <div key={s.code} className="cap">
            <div className="img">
              <CropImage
                crossing={s.code}
                available={s.faceCrop}
                style={{ width: '100%', height: '100%', borderRadius: 0 }}
              />
            </div>
            <div className="meta">
              <b className="num">{fmtTime(s.at, true)}</b>
              <span>
                {s.direction} · {s.cameraName}
              </span>
              <span className="muted">
                Eye distance {s.quality ? String(s.quality.eyePx) : DASH} px · sharpness{' '}
                {s.quality ? String(s.quality.sharp) : DASH}
              </span>
              {item.type === 'UNKNOWN' && (
                <Button
                  size="sm"
                  className="mt-4"
                  onClick={() => {
                    onPickBest(s.code);
                    onClose();
                  }}
                >
                  Use as best crop
                </Button>
              )}
            </div>
          </div>
        ))}
      </div>
    </Modal>
  );
}
