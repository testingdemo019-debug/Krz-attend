import type { components } from '@/api/schema';
import { Chip } from '@/components/ui/Chip';
import '@/features/review/ReviewQueue.module.css';

export type FaceQuality = components['schemas']['FaceQuality'];

function QualityChip({ label, ok, value }: { label: string; ok: boolean; value: string }) {
  return (
    <Chip tone={ok ? 'green' : 'amber'} icon={ok ? 'check' : 'alert'} title={label}>
      {label} {value}
    </Chip>
  );
}

/**
 * The prototype's qualityChips: face size, sharpness, pose and lighting of a face crop against the active quality
 * gate (the server decides what passes). Lighting is left out for crossings recorded before it was measured.
 */
export function QualityChips({ quality }: { quality: FaceQuality | null }) {
  if (!quality) return null;
  return (
    <div className="qchips">
      <QualityChip label="Face size" ok={quality.eyePxOk} value={`${String(quality.eyePx)} px`} />
      <QualityChip label="Sharpness" ok={quality.sharpOk} value={String(quality.sharp)} />
      <QualityChip label="Pose" ok={quality.poseOk} value={`${String(quality.pose)}°`} />
      {quality.light !== null && (
        <QualityChip label="Lighting" ok={quality.lightOk === true} value={String(quality.light)} />
      )}
    </div>
  );
}
