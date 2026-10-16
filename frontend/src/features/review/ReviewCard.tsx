/**
 * One open item (the prototype's reviewCardHTML): selection and crops, title and where/when, the snapshot with its
 * quality chips, the type's explanation and candidates, and the decision buttons with their keys.
 */
import type { ReactNode } from 'react';
import type { ReviewCandidate, ReviewItem, ReviewSighting } from '@/api/review';
import { useDetailDrawers } from '@/app/DetailDrawers';
import { DirectionChip, LivenessBadge } from '@/components/domain/Chips';
import { CropImage, FrameThumb } from '@/components/domain/CropImage';
import { QualityChips } from '@/components/domain/QualityChips';
import { Icon } from '@/components/icons/Icon';
import { Avatar } from '@/components/ui/Avatar';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { Kbd } from '@/components/ui/Kbd';
import { cx } from '@/lib/cx';
import { fmtDateTime, fmtTime, relTime } from '@/lib/format';
import { useCan, useShowScores } from '@/lib/permissions';
import { plural, shortName } from '@/lib/text';
import { CONFIDENCE, term } from '@/lib/vocabulary';
import type { CardChoice, useReviewDecisions } from './useReviewDecisions';
import './ReviewQueue.module.css';

type Actions = ReturnType<typeof useReviewDecisions>['actions'];

/** Without settings.recognition the API sends no scores, so the bar shows the band (security §4). */
const BAND_WIDTH: Record<string, number> = { HIGH: 85, MEDIUM: 60, LOW: 35 };

const key = (k: string) => <Kbd style={{ marginLeft: 'auto' }}>{k}</Kbd>;

/** The prototype's bestSighting: the chosen crossing, else the server's best. */
export function bestOf(item: ReviewItem, choice: CardChoice): ReviewSighting | undefined {
  const code = choice.best ?? item.bestSighting;
  return item.sightings.find(s => s.code === code) ?? item.sightings[0];
}

function Candidate({ candidate: c, top }: { candidate: ReviewCandidate; top: boolean }) {
  const showScores = useShowScores();
  const band = term(CONFIDENCE, c.confidence)?.label ?? c.confidence;
  const width =
    c.score !== null ? Math.min(Math.max((c.score - 0.3) / 0.7, 0), 1) * 100 : (BAND_WIDTH[c.confidence] ?? 0);
  return (
    <div className={cx('cand', top && 'top')}>
      <Avatar id={c.employeeId} name={c.name} size="sm" />
      <span className="ellipsis">
        <b>{c.name}</b> <span className="muted xs">{c.department ?? '—'}</span>
      </span>
      <span className="row gap-4">
        <span
          className="bar grow"
          title={showScores && c.score !== null ? `Similarity ${c.score.toFixed(3)}` : `${band} confidence`}
        >
          <i style={{ width: `${String(width)}%` }} />
        </span>
        <span className="xs num" style={{ width: 46, textAlign: 'right' }}>
          {showScores && c.score !== null ? c.score.toFixed(2) : band}
        </span>
      </span>
    </div>
  );
}

function Candidates({ item }: { item: ReviewItem }) {
  return (
    <div className="mt-8">
      <div className="label mb-8">
        {item.candidates.length ? `Top ${plural(item.candidates.length, 'candidate')}` : 'No candidates'}
      </div>
      {item.candidates.map((c, i) => (
        <Candidate key={c.employeeId} candidate={c} top={i === 0} />
      ))}
    </div>
  );
}

export function ReviewCard({
  item,
  current,
  leaving,
  selected,
  onSelect,
  choice,
  onChoice,
  actions,
  onGallery,
  unknownHours,
  now,
}: {
  item: ReviewItem;
  current: boolean;
  leaving: boolean;
  selected: boolean;
  onSelect: () => void;
  choice: CardChoice;
  onChoice: (choice: CardChoice) => void;
  actions: Actions;
  onGallery: () => void;
  unknownHours: number;
  now: number;
}) {
  const canEvents = useCan('events.view');
  const { openEvent } = useDetailDrawers();
  const best = bestOf(item, choice);
  const first = item.sightings[0];
  const last = item.sightings.at(-1);
  const top = item.candidates[0] ?? null;
  const eligibility = best?.template ?? { ok: false, why: 'No crop' };
  const n = item.sightings.length;

  const templateBox = (
    <label className={cx('check small mt-4', !eligibility.ok && 'muted')}>
      <input
        type="checkbox"
        checked={choice.addTemplate && eligibility.ok}
        disabled={!eligibility.ok}
        onChange={event => {
          onChoice({ ...choice, addTemplate: event.target.checked });
        }}
      />
      <span>
        Add this crop as a face template
        <br />
        <span className="xs" style={{ color: eligibility.ok ? 'var(--text-3)' : 'var(--amber)' }}>
          {eligibility.why}
        </span>
      </span>
    </label>
  );
  const assign = (text: ReactNode, primary = false) => (
    <Button
      variant={primary ? 'primary' : 'default'}
      icon="userCheck"
      iconSize="default"
      onClick={() => {
        actions.assign(item, choice);
      }}
    >
      {text} {key('A')}
    </Button>
  );
  const notEmployee = (
    <Button
      icon="user"
      iconSize="default"
      onClick={() => {
        void actions.notEmployee(item);
      }}
    >
      Not an employee {key('V')}
    </Button>
  );
  const reject = (
    <Button
      icon="ban"
      iconSize="default"
      onClick={() => {
        actions.reject(item);
      }}
    >
      Reject — bad image {key('R')}
    </Button>
  );
  const confirm = (text: string) =>
    top && (
      <Button
        variant="primary"
        icon="check"
        iconSize="default"
        onClick={() => {
          actions.confirm(item, choice);
        }}
      >
        {text} {key('C')}
      </Button>
    );
  const galleryCrop = (label: string) =>
    best && <CropImage crossing={best.code} size="xl" available={best.faceCrop} onOpen={onGallery} openLabel={label} />;

  let title: string;
  let crops: ReactNode;
  let main: ReactNode;
  let buttons: ReactNode;
  if (item.type === 'UNKNOWN') {
    const label = item.label ?? 'Unknown face';
    const ins = item.sightings.filter(s => s.direction === 'IN');
    const outs = item.sightings.filter(s => s.direction === 'OUT');
    title = `${label} · ${plural(n, 'crossing')} · ${fmtTime(first?.at)}–${fmtTime(last?.at)}`;
    crops = (
      <>
        {galleryCrop(`Open all crops for ${label}`)}
        <div className="rv-mini">
          {item.sightings.slice(0, 8).map(s => (
            <CropImage
              key={s.code}
              crossing={s.code}
              available={s.faceCrop}
              onOpen={() => {
                onChoice({ ...choice, best: s.code });
              }}
              openLabel={`Use crossing at ${fmtTime(s.at)}`}
              style={s.code === best?.code ? { outline: '2px solid var(--accent)' } : undefined}
            />
          ))}
        </div>
      </>
    );
    main = (
      <>
        <div className="small">
          {ins.length > 0 && `${plural(ins.length, 'IN')} (${ins.map(s => fmtTime(s.at)).join(', ')})`}
          {ins.length > 0 && outs.length > 0 && ' · '}
          {outs.length > 0 &&
            `${plural(outs.length, 'OUT')} (${outs.map(s => fmtTime(s.at)).join(', ')}) — linked to the IN by same-day body match`}
        </div>
        <p className="muted small mt-8">
          Assigning sends every crossing through the punch rules for that employee: the first IN becomes the check-in, a
          later OUT becomes the check-out.
        </p>
      </>
    );
    buttons = (
      <>
        {assign(`Assign ${n > 1 ? `all ${String(n)} crossings` : 'to employee'}…`, true)}
        {notEmployee}
        {reject}
        {templateBox}
        <p className="xs muted">Crops of unknown faces are purged after {unknownHours} hours.</p>
      </>
    );
  } else if (item.type === 'REID') {
    title = top ? `Back-view exit · ${top.name}?` : item.typeLabel;
    crops = (
      <div className="pair" style={{ width: 220 }}>
        <figure>
          {first && <CropImage crossing={first.code} kind="body" size="lg" available={first.bodyCrop} />}
          <figcaption>Leaving · {fmtTime(item.createdAt)}</figcaption>
        </figure>
        <figure>
          {item.cameIn ? (
            <CropImage crossing={item.cameIn.code} size="lg" available={item.cameIn.faceCrop} />
          ) : (
            <CropImage crossing={item.code} size="lg" available={false} />
          )}
          <figcaption>{item.cameIn ? `Came in · ${fmtTime(item.cameIn.at)}` : 'No IN today'}</figcaption>
        </figure>
      </div>
    );
    main = (
      <>
        <Callout tone="neutral" icon="body" className="small">
          Identified by clothing and build, compared only with people who came in today. {item.reason}.
        </Callout>
        <Candidates item={item} />
      </>
    );
    buttons = (
      <>
        {top && confirm(`Confirm ${shortName(top.name)}`)}
        {assign('Assign to employee…')}
        {reject}
      </>
    );
  } else if (item.type === 'LIVENESS') {
    title = top ? `Liveness alert · the face resembles ${top.name}` : item.typeLabel;
    crops = (
      <>
        {galleryCrop('Open crop')}
        <LivenessBadge result="FAILED" />
      </>
    );
    main = (
      <>
        <Callout tone="danger" icon="shieldAlert" className="small">
          <b>Possible photo or phone spoof.</b> {item.reason}. No punch was created.
        </Callout>
        <Candidates item={item} />
      </>
    );
    buttons = (
      <>
        <Button
          variant="danger"
          icon="shieldAlert"
          iconSize="default"
          onClick={() => {
            void actions.spoof(item);
          }}
        >
          Confirm spoof attempt
        </Button>
        {top && confirm(`False alarm — it’s ${shortName(top.name)}`)}
        {assign('False alarm — someone else…')}
        {reject}
      </>
    );
  } else {
    title = top ? `Uncertain match · ${top.name}?` : item.typeLabel;
    crops = galleryCrop('Open crop');
    main = (
      <>
        <Callout tone="warn" icon="help" className="small">
          {item.reason ?? 'Score near the threshold'}.
        </Callout>
        <Candidates item={item} />
      </>
    );
    buttons = (
      <>
        {top && confirm(`Confirm ${shortName(top.name)}`)}
        {assign('Assign to employee…')}
        {notEmployee}
        {reject}
        {templateBox}
      </>
    );
  }

  const shown = item.type === 'UNKNOWN' ? best : first;
  return (
    <article
      className={cx('rv-card', current && 'current', leaving && 'leaving')}
      data-review={item.code}
      aria-label={title}
    >
      <div className={cx('rv-crops', item.type === 'REID' && 'pair-wide')}>
        <label className="check xs" style={{ alignItems: 'center' }}>
          <input type="checkbox" checked={selected} onChange={onSelect} aria-label={`Select ${item.code}`} />{' '}
          <span className="mono muted">{item.code}</span>
        </label>
        {crops}
      </div>
      <div style={{ minWidth: 0 }}>
        <h3 style={{ fontSize: 15.5 }}>{title}</h3>
        <div className="row wrap gap-8 small muted mt-4">
          <Icon name="cctv" size="sm" />
          {shown?.cameraName} · {item.direction && <DirectionChip direction={item.direction} />} ·{' '}
          <span className="num">{fmtDateTime(item.createdAt)}</span>{' '}
          {item.waiting && (
            <Chip tone="amber" icon="hourglass">
              Waiting {relTime(item.createdAt, now).replace(' ago', '')}
            </Chip>
          )}
        </div>
        <div className="grid rv-detail mt-12">
          <div>
            {shown && <FrameThumb crossing={shown.code} available={shown.bodyCrop} />}
            <QualityChips quality={shown?.quality ?? null} />
            {shown && canEvents && (
              <Button
                variant="ghost"
                size="sm"
                icon="list"
                className="mt-4"
                onClick={() => {
                  openEvent(shown.code);
                }}
              >
                Crossing {shown.code}
              </Button>
            )}
          </div>
          <div>{main}</div>
        </div>
      </div>
      <div className="rv-actions">{buttons}</div>
    </article>
  );
}
