/**
 * Settings → Recognition: thresholds and quality limits. Saving creates a new threshold profile (TP-n); an earlier
 * one can be used again. The impact preview scores the last days' crossings again with the values on the page. The
 * per-camera liveness flags are saved with the page. Model names and versions are shown here only (F6.8).
 */
import { useRef, useState } from 'react';
import { Link } from 'react-router';
import {
  useActivateProfile,
  useImpact,
  useRecognitionSettings,
  useSaveRecognition,
  type ImpactView,
  type RecognitionView,
  type ThresholdValues,
} from '@/api/settings';
import { useConfirm } from '@/components/feedback/ConfirmDialog';
import { ErrorState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Switch } from '@/components/form/Switch';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { fmtDate } from '@/lib/format';
import { useDraft } from '@/lib/useDraft';
import { CalibrationTag, SettingsCard } from './SettingsCard';
import '@/components/form/Slider.module.css';
import '@/components/ui/StatStrip.module.css';
import './Recognition.module.css';

const TITLE = 'Recognition';
const SUB =
  'Thresholds and quality limits. Saving creates a new threshold profile; you can switch back to an earlier one.';
const IMPACT_DELAY_MS = 250;

/** What each model does, by the worker's model name (unknown names are shown as they are). */
const MODEL_ROLES: Readonly<Record<string, string>> = {
  yunet: 'Face detector',
  sface: 'Face embedding',
  yolox: 'Person detection and tracking',
  osnet: 'Back-view Re-ID',
  'minifasnet-v2': 'Liveness',
  'minifasnet-v1se': 'Liveness (second model)',
};

interface Values {
  thresholds: ThresholdValues;
  /** Camera code → liveness on. */
  liveness: Record<string, boolean>;
}

type Impact = Pick<ThresholdValues, 'accept' | 'reviewBand' | 'margin'>;

export function RecognitionSettings() {
  const view = useRecognitionSettings();
  if (view.isError) return <ErrorState what="the settings" onRetry={() => void view.refetch()} />;
  if (!view.data) {
    return (
      <SettingsCard title={TITLE} sub={SUB}>
        <SkeletonRows rows={8} columns={2} />
      </SettingsCard>
    );
  }
  return (
    <RecognitionForm
      view={view.data}
      reload={() => {
        void view.refetch();
      }}
    />
  );
}

function RecognitionForm({ view, reload }: { view: RecognitionView; reload: () => void }) {
  const save = useSaveRecognition();
  const source: Values = {
    thresholds: view.active.values,
    liveness: Object.fromEntries(view.liveness.map(l => [l.cameraCode, l.enabled])),
  };
  const draft = useDraft({
    title: TITLE,
    source,
    save: d =>
      save.mutateAsync({
        thresholds:
          JSON.stringify(d.thresholds) === JSON.stringify(source.thresholds)
            ? null
            : { basedOn: view.active.code, note: 'Edited in Settings', values: d.thresholds },
        liveness: Object.entries(d.liveness)
          .filter(([code, on]) => source.liveness[code] !== on)
          .map(([cameraCode, enabled]) => ({ cameraCode, enabled })),
      }),
    onStale: reload,
  });
  const d = draft.value.thresholds;
  const pick = (v: ThresholdValues): Impact => ({ accept: v.accept, reviewBand: v.reviewBand, margin: v.margin });
  const [impactValues, setImpactValues] = useState<Impact>(() => pick(d));
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const impact = useImpact(impactValues).data;

  const set = (patch: Partial<ThresholdValues>) => {
    const next = { ...d, ...patch };
    draft.set(current => ({ ...current, thresholds: next }));
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      setImpactValues(pick(next));
    }, IMPACT_DELAY_MS);
  };
  const slider = (
    key: keyof ThresholdValues,
    label: string,
    min: number,
    max: number,
    step: number,
    help: string,
    int = false,
  ) => {
    const value = Number(d[key]);
    return (
      <div className="field mb-12">
        <label htmlFor={`rc-${key}`}>
          {label} <CalibrationTag />
        </label>
        <div className="slider-row">
          <input
            id={`rc-${key}`}
            type="range"
            min={Math.min(min, value)}
            max={Math.max(max, value)}
            step={step}
            value={value}
            onChange={e => {
              set({ [key]: Number(e.target.value) });
            }}
          />
          <output className="mono" htmlFor={`rc-${key}`}>
            {int ? String(value) : value.toFixed(2)}
          </output>
        </div>
        <div className="help">{help}</div>
      </div>
    );
  };

  return (
    <SettingsCard title={TITLE} sub={SUB} draft={draft}>
      <div className="set-section">
        <h3>Model</h3>
        <KeyValueList
          pairs={
            view.model.available
              ? [
                  ...view.model.models.map(m => [MODEL_ROLES[m.name] ?? m.name, `${m.name} ${m.version}`] as const),
                  ['Pipeline', `krz-worker ${view.model.version ?? '—'} · ${view.model.provider ?? '—'}`],
                  [
                    'Licence',
                    <Chip tone="grey" icon="info" className="chip-wrap">
                      <Link to="/settings/system">In System → About</Link>
                    </Chip>,
                  ],
                ]
              : [['Models', 'Not available — the recognition worker did not answer']]
          }
        />
      </div>
      <div className="rec-grid">
        <div>
          <div className="set-section">
            <h3>Face matching (IN)</h3>
            {slider(
              'accept',
              'Accept threshold',
              0.4,
              0.9,
              0.01,
              'At or above this, the face is matched automatically. Higher means fewer wrong matches but more review items.',
            )}
            {slider(
              'reviewBand',
              'Review band',
              0.02,
              0.2,
              0.01,
              'Scores this far below the accept threshold go to the Review Queue instead of being unknown.',
            )}
            {slider(
              'margin',
              'Minimum gap between the top two candidates',
              0.02,
              0.25,
              0.01,
              'If the best two people are closer than this, the crossing goes to review.',
            )}
          </div>
          <div className="set-section">
            <h3>Back-view Re-ID (OUT)</h3>
            <div className="row between mb-8">
              <span>Match people walking away by clothing and build</span>
              <Switch
                checked={d.reidOn}
                label={d.reidOn ? 'On' : 'Off'}
                onChange={e => {
                  set({ reidOn: e.target.checked });
                }}
              />
            </div>
            {d.reidOn ? (
              slider(
                'reidThreshold',
                'Re-ID auto-accept threshold',
                0.5,
                0.95,
                0.01,
                'Body matches above this create an OUT with method Re-ID. Lower matches become “Back-view exit” review items.',
              )
            ) : (
              <Callout tone="warn" icon="alert" className="small">
                With Re-ID off, every OUT without a visible face goes to review.
              </Callout>
            )}
            <div className="row between">
              <span>Match against today’s IN crossings only</span>
              <Switch
                checked
                disabled
                label="Always on"
                tooltip="Clothing changes between days, so Re-ID never matches across days"
              />
            </div>
          </div>
          <div className="set-section">
            <h3>Face quality gate</h3>
            {slider(
              'minEyePx',
              'Minimum face size (eye distance, px)',
              20,
              60,
              1,
              'Smaller faces are not used for matching.',
              true,
            )}
            {slider('maxYaw', 'Maximum head turn (degrees)', 10, 45, 1, '', true)}
            {slider('sharpMin', 'Minimum sharpness', 10, 90, 1, '', true)}
          </div>
          <div className="set-section">
            <h3>Liveness</h3>
            {view.liveness.map(l => {
              const on = draft.value.liveness[l.cameraCode] ?? l.enabled;
              return (
                <div key={l.cameraCode} className="row between mb-8">
                  <span>{l.cameraName}</span>
                  <Switch
                    checked={on}
                    label={on ? 'On' : 'Off'}
                    onChange={e => {
                      draft.set(current => ({
                        ...current,
                        liveness: { ...current.liveness, [l.cameraCode]: e.target.checked },
                      }));
                    }}
                  />
                </div>
              );
            })}
            <p className="help">Passive check against photos and phone screens. Shown only as passed or failed.</p>
          </div>
        </div>
        <div style={{ position: 'sticky', top: 'calc(var(--topbar-h) + 16px)' }}>
          <ImpactCard impact={impact} values={d} saved={view.active.values} />
          <Profiles view={view} onActivated={draft.reset} />
        </div>
      </div>
    </SettingsCard>
  );
}

function ImpactCard({
  impact,
  values,
  saved,
}: {
  impact: ImpactView | undefined;
  values: ThresholdValues;
  saved: ThresholdValues;
}) {
  const delta = (x: number, y: number) =>
    x === y ? null : (
      <span className="small" style={{ color: `var(--${x > y ? 'amber' : 'green'})` }}>
        {x > y ? '+' : ''}
        {x - y}
      </span>
    );
  const days = impact?.days ?? 14;
  const a = impact?.proposed ?? { accepted: 0, review: 0, unknown: 0 };
  const b = impact?.current ?? a;
  return (
    <div className="card pad">
      <h3>Impact preview</h3>
      <p className="help mb-8">
        The last {days} days of crossings ({(impact?.total ?? 0).toLocaleString('en-IN')}) scored again with these
        values.
      </p>
      <div className="chart">{impact && <Histogram impact={impact} values={values} saved={saved} />}</div>
      <div className="stat-strip mt-12">
        <div className="stat">
          <div className="v">
            {a.accepted} {delta(a.accepted, b.accepted)}
          </div>
          <div className="l">Matched automatically</div>
        </div>
        <div className="stat">
          <div className="v">
            {a.review} {delta(a.review, b.review)}
          </div>
          <div className="l">To review</div>
        </div>
        <div className="stat">
          <div className="v">
            {a.unknown} {delta(a.unknown, b.unknown)}
          </div>
          <div className="l">Unknown</div>
        </div>
      </div>
      <p className="muted xs mt-8">
        About {Math.round(a.review / days)} review items a day (now {Math.round(b.review / days)}).
      </p>
    </div>
  );
}

/** The prototype's histogramSVG: the score bins coloured by the values, the review band and the accept line. */
function Histogram({ impact, values, saved }: { impact: ImpactView; values: ThresholdValues; saved: ThresholdValues }) {
  const W = 760;
  const H = 220;
  const L = 36;
  const B = 30;
  const T = 10;
  const R = 10;
  const { low, high, bins } = impact;
  const max = Math.max(1, ...bins);
  const bw = (W - L - R) / Math.max(1, bins.length);
  const X = (v: number) => L + ((v - low) / (high - low)) * (W - L - R);
  const Y = (c: number) => T + (H - T - B) * (1 - c / max);
  const ticks = [0.2, 0.4, 0.6, 0.8, 1].filter(t => t >= low && t <= high);
  return (
    <svg
      viewBox={`0 0 ${String(W)} ${String(H)}`}
      role="img"
      aria-label="Histogram of similarity scores with the accept threshold"
    >
      <rect
        x={X(values.accept - values.reviewBand)}
        y={T}
        width={X(values.accept) - X(values.accept - values.reviewBand)}
        height={H - T - B}
        fill="var(--amber-bg)"
      />
      {bins.map((c, i) => {
        const mid = low + ((i + 0.5) * (high - low)) / bins.length;
        const fill =
          mid >= values.accept
            ? 'var(--green-line)'
            : mid >= values.accept - values.reviewBand
              ? 'var(--amber-line)'
              : 'var(--grey-line)';
        return (
          <rect key={i} x={L + i * bw + 1} y={Y(c)} width={Math.max(0, bw - 2)} height={H - B - Y(c)} fill={fill}>
            <title>
              {mid.toFixed(2)}: {c} crossings
            </title>
          </rect>
        );
      })}
      {saved.accept !== values.accept && (
        <>
          <line
            x1={X(saved.accept)}
            x2={X(saved.accept)}
            y1={T}
            y2={H - B}
            stroke="var(--text-3)"
            strokeDasharray="4 3"
          />
          <text x={X(saved.accept) + 4} y={T + 22} fontSize="11" fill="var(--text-3)">
            saved {saved.accept.toFixed(2)}
          </text>
        </>
      )}
      <line x1={X(values.accept)} x2={X(values.accept)} y1={T} y2={H - B} stroke="var(--text)" strokeWidth="2" />
      <text x={X(values.accept) + 4} y={T + 10} fontSize="11" fontWeight="600" fill="var(--text)">
        accept {values.accept.toFixed(2)}
      </text>
      <line x1={L} x2={W - R} y1={H - B} y2={H - B} stroke="var(--chart-grid)" />
      {ticks.map(t => (
        <text key={t} x={X(t)} y={H - 10} textAnchor="middle" fontSize="11" fill="var(--text-3)">
          {t.toFixed(1)}
        </text>
      ))}
    </svg>
  );
}

function Profiles({ view, onActivated }: { view: RecognitionView; onActivated: () => void }) {
  const confirm = useConfirm();
  const activate = useActivateProfile();
  const use = async (code: string, accept: number) => {
    const ok = await confirm({
      title: `Switch to ${code}?`,
      message: `Accept threshold ${accept.toFixed(2)} (now ${view.active.values.accept.toFixed(2)}). Applies to new crossings only.`,
      confirmText: 'Switch profile',
    });
    if (!ok) return;
    activate.mutate(code, {
      onSuccess: () => {
        onActivated();
        toast(`Now using ${code}`, { type: 'success', logged: true });
      },
      onError: error => toast(error.message, { type: 'error' }),
    });
  };
  return (
    <div className="card pad mt-12">
      <h3>Threshold profiles</h3>
      <ScrollArea className="dt-wrap mt-8" label="Threshold profiles">
        <table className="dt compact">
          <tbody>
            {view.profiles.map(p => (
              <tr key={p.code}>
                <td>
                  <b className="mono">{p.code}</b>
                  {p.active && (
                    <>
                      {' '}
                      <Chip tone="green">Active</Chip>
                    </>
                  )}
                  <div className="muted xs">
                    {fmtDate(p.createdAt)} · {p.createdBy} · accept {p.values.accept.toFixed(2)}
                  </div>
                  <div className="xs">{p.note ?? ''}</div>
                </td>
                <td>
                  {!p.active && (
                    <Button size="sm" onClick={() => void use(p.code, p.values.accept)}>
                      Use
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollArea>
    </div>
  );
}
