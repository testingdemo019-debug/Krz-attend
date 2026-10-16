/** The prototype's shared chips and labels, each from the one vocabulary (F3, F6.9). */
import { Icon } from '@/components/icons/Icon';
import { Chip } from '@/components/ui/Chip';
import { useShowScores } from '@/lib/permissions';
import {
  ATTENDANCE_STATUS,
  CONFIDENCE,
  CONSENT_STATUS,
  DECISIONS,
  EFFECTS,
  FACE_STATUS,
  HRMS_STATUS,
  LIVENESS,
  METHODS,
  term,
} from '@/lib/vocabulary';
import '@/components/ui/Chip.module.css';

const MUTED_DASH = <span className="muted">—</span>;

/** IN (green) or OUT (blue) with the arrow. */
export function DirectionChip({ direction }: { direction: string }) {
  return direction === 'IN' ? (
    <span className="dir dir-in">
      <Icon name="arrowIn" size="sm" />
      IN
    </span>
  ) : (
    <span className="dir dir-out">
      <Icon name="arrowOut" size="sm" />
      OUT
    </span>
  );
}

export function EffectChip({ effect }: { effect: string | null }) {
  const t = term(EFFECTS, effect);
  if (!effect) return MUTED_DASH;
  return <Chip tone={t?.tone ?? 'grey'}>{t?.label ?? effect}</Chip>;
}

export function FaceStatusChip({ status }: { status: string }) {
  const t = term(FACE_STATUS, status);
  return (
    <Chip tone={t?.tone} icon={t?.icon}>
      {t?.label ?? status}
    </Chip>
  );
}

/** "Face" / "Re-ID" / "Review", or the decision when the crossing has no method (the prototype's feed line). */
export function methodLabel(method: string | null, decision?: string): string {
  const m = term(METHODS, method);
  if (m) return m.label;
  return decision ? (term(DECISIONS, decision) ?? decision) : '—';
}

/** The method with its icon (.src), or a muted dash. */
export function MethodLabel({ method }: { method: string | null }) {
  const m = term(METHODS, method);
  if (!m) return MUTED_DASH;
  return (
    <span className="src">
      <Icon name={m.icon} size="sm" />
      {m.label}
    </span>
  );
}

export function DecisionText({ decision }: { decision: string }) {
  return <>{term(DECISIONS, decision) ?? decision}</>;
}

export function LivenessBadge({ result }: { result: string | null }) {
  const t = term(LIVENESS, result);
  if (!t) return MUTED_DASH;
  return (
    <Chip tone={t.tone} icon={t.icon}>
      {t.label}
    </Chip>
  );
}

export function HrmsStatusChip({ status }: { status: string }) {
  const t = term(HRMS_STATUS, status);
  return (
    <Chip tone={t?.tone ?? 'grey'} icon={t?.icon ?? 'dot'}>
      {t?.label ?? status}
    </Chip>
  );
}

/**
 * The prototype's confidenceHTML: the raw score with settings.recognition, otherwise the band chip (scores are
 * never shown without that permission; the API leaves them out too).
 */
export function ConfidenceBadge({ band, score }: { band: string | null; score: number | null }) {
  const showScores = useShowScores();
  if (showScores && score !== null) return <span className="mono">{score.toFixed(3)}</span>;
  const t = term(CONFIDENCE, band);
  if (!t) return MUTED_DASH;
  return <Chip tone={t.tone}>{t.label}</Chip>;
}

/**
 * The prototype's statusChip: the day status with its help text, "· tentative" while a check-out can still change,
 * and a separate Late chip when a late day has another status.
 */
export function AttendanceStatusChip({
  status,
  tentative = false,
  late = false,
}: {
  status: string | null;
  tentative?: boolean;
  late?: boolean;
}) {
  if (!status) return MUTED_DASH;
  const t = term(ATTENDANCE_STATUS, status);
  return (
    <>
      <Chip tone={t?.tone ?? 'grey'} icon={t?.icon ?? 'dot'} title={t?.description}>
        {t?.label ?? status}
        {tentative && ' · tentative'}
      </Chip>
      {late && status !== 'LATE' && (
        <>
          {' '}
          <Chip tone="amber" icon="clock">
            Late
          </Chip>
        </>
      )}
    </>
  );
}

/** The prototype's trackingChip: Tracked, or Not tracked with the reason as its title. */
export function TrackingChip({ tracked, label }: { tracked: boolean; label: string }) {
  return tracked ? (
    <Chip tone="green" icon="eye">
      Tracked
    </Chip>
  ) : (
    <Chip tone="grey" icon="eyeOff" title={label}>
      Not tracked
    </Chip>
  );
}

export function ConsentChip({ status }: { status: string }) {
  const t = term(CONSENT_STATUS, status) ?? CONSENT_STATUS.NONE;
  return (
    <Chip tone={t.tone} icon={t.icon}>
      {t.label}
    </Chip>
  );
}

/** The prototype's accountChip: shown only for a disabled account, with the reason as its title. */
export function AccountChip({ enabled, reason }: { enabled: boolean; reason?: string | null }) {
  if (enabled) return null;
  return (
    <Chip tone="grey" icon="ban" title={reason ?? 'Disabled in Microsoft Entra ID'}>
      Account disabled
    </Chip>
  );
}

/** R16: employees created by the directory sync until their employment is entered. */
export function EmploymentMissingChip() {
  return (
    <Chip tone="amber" icon="alert" title="Department, designation, location or join date is not set yet">
      Employment details missing
    </Chip>
  );
}
