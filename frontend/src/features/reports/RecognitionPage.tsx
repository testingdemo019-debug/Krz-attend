/**
 * Recognition quality (the prototype's #/reports/recognition): over the last 7, 14 or 21 days, how often the entrance
 * camera identified people on its own and where people stepped in. The KPIs, the per-day chart, the day-by-day table,
 * the employees to re-enrol and what reviewers decided.
 */
import { useSearchParams } from 'react-router';
import { useRecognition, type Recognition, type RecognitionDay } from '@/api/reports';
import { HBarChart, StackedBarChart, type Segment } from '@/components/charts/BarCharts';
import { Legend } from '@/components/charts/Legend';
import { KpiCard } from '@/components/data/KpiCard';
import { ConfidenceBadge, FaceStatusChip } from '@/components/domain/Chips';
import { PersonCell } from '@/components/domain/PersonCell';
import { EmptyState, ErrorState } from '@/components/feedback/EmptyState';
import { Card } from '@/components/layout/Card';
import { PageHead } from '@/components/layout/PageHead';
import { ButtonLink } from '@/components/ui/Button';
import { Segmented } from '@/components/ui/Segmented';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { addDays, fmtDateShort, fmtDay, fmtDuration, todayDay } from '@/lib/format';
import { useCan, useShowScores } from '@/lib/permissions';
import { useNow } from '@/lib/useNow';
import { CONFIDENCE, term } from '@/lib/vocabulary';

const PERIODS = ['7', '14', '21'] as const;
type Period = (typeof PERIODS)[number];

/** The per-day chart's segments, in the prototype's order and colours. */
const SEGMENTS: readonly Segment[] = [
  { key: 'faceAuto', label: 'Face (auto)', color: 'var(--green-line)' },
  { key: 'reidAuto', label: 'Re-ID (auto)', color: 'var(--purple-line)' },
  { key: 'review', label: 'Review', color: 'var(--amber-line)' },
  { key: 'notIdentified', label: 'Not identified', color: 'var(--grey-line)' },
];

/** "84%", or "—" without a base. */
function pct(part: number, whole: number): string {
  return whole ? `${String(Math.round((100 * part) / whole))}%` : '—';
}

export function RecognitionPage() {
  const now = useNow(60_000);
  const [params, setParams] = useSearchParams();
  const days: Period = PERIODS.find(p => p === params.get('days')) ?? '14';
  const to = todayDay(now);
  const report = useRecognition(addDays(to, 1 - Number(days)), to);
  return (
    <>
      <PageHead
        title="Recognition quality"
        sub="How often the single entrance camera identified people on its own, and where people had to step in."
        actions={
          <Segmented
            label="Period"
            segments={PERIODS.map(p => ({ value: p, label: `Last ${p} days` }))}
            value={days}
            onChange={p => {
              setParams(
                current => {
                  const next = new URLSearchParams(current);
                  if (p === '14') next.delete('days');
                  else next.set('days', p);
                  return next;
                },
                { replace: true },
              );
            }}
          />
        }
      />
      {report.isError ? (
        <ErrorState what="the report" onRetry={() => void report.refetch()} />
      ) : (
        report.data && <Body report={report.data} />
      )}
    </>
  );
}

function Body({ report }: { report: Recognition }) {
  const canEnrol = useCan('people.enrol');
  const canTune = useCan('settings.recognition');
  const showScores = useShowScores();
  const t = report.totals;
  const band = term(CONFIDENCE, report.faceConfidence);
  return (
    <>
      <div className="kpis">
        <KpiCard
          label="Crossings"
          value={t.crossings.toLocaleString('en-IN')}
          foot={`${String(t.ins)} IN · ${String(t.outs)} OUT`}
          icon="list"
          tone="blue"
          to="/events"
        />
        <KpiCard
          label="Identified automatically"
          value={pct(t.identifiedAutomatically, t.crossings)}
          foot="Face or Re-ID, no review"
          icon="checkCircle"
          tone="green"
          to="/events"
        />
        <KpiCard
          label="IN by face"
          value={pct(t.inByFace, t.ins)}
          foot="Facing the camera"
          icon="face"
          tone="green"
          to="/events"
        />
        <KpiCard
          label="OUT by Re-ID"
          value={pct(t.outByReid, t.outs)}
          foot="Back view, same-day match"
          icon="body"
          tone="purple"
          to="/events"
        />
        <KpiCard
          label="Sent to review"
          value={pct(t.sentToReview, t.crossings)}
          foot={`${String(t.resolvedInReview)} resolved to an employee`}
          icon="review"
          tone="amber"
          to="/review?tab=resolved"
        />
        <KpiCard
          label="Liveness alerts"
          value={t.livenessAlerts}
          foot="Possible spoofs"
          icon="shieldAlert"
          tone="red"
          to="/review?tab=liveness"
        />
        <KpiCard
          label={showScores ? 'Average face match' : 'Face match confidence'}
          value={
            showScores
              ? report.averageFaceScore === null
                ? '—'
                : report.averageFaceScore.toFixed(3)
              : (band?.label ?? '—')
          }
          foot={`Threshold profile ${report.thresholdProfile ?? '—'}`}
          icon="activity"
          tone="grey"
          to={canTune ? '/settings/recognition' : '/reports/recognition'}
        />
      </div>
      <Card
        title="Per day"
        className="mb-16"
        bodyClassName="chart"
        actions={<Legend entries={SEGMENTS.map(s => ({ label: s.label, colour: s.color }))} />}
      >
        <StackedBarChart
          items={report.days.map(d => ({
            label: fmtDateShort(d.day),
            values: { faceAuto: d.faceAuto, reidAuto: d.reidAuto, review: d.review, notIdentified: d.notIdentified },
          }))}
          segments={SEGMENTS}
        />
      </Card>
      <DayByDay days={report.days} />
      <div className="grid grid-2">
        <Card title="Employees to re-enrol" flush>
          {report.reenrol.length ? (
            <ScrollArea className="dt-wrap" label="Employees to re-enrol">
              <table className="dt">
                <thead>
                  <tr>
                    <th>Employee</th>
                    <th>{showScores ? 'Avg score' : 'Confidence'}</th>
                    <th className="num">Reviews</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {report.reenrol.map(w => (
                    <tr key={w.id}>
                      <td>
                        <PersonCell id={w.id} name={w.name} sub={<FaceStatusChip status={w.faceStatus} />} />
                      </td>
                      <td>
                        <ConfidenceBadge band={w.confidence} score={w.averageScore} />
                      </td>
                      <td className="num">{w.reviews}</td>
                      <td>
                        {canEnrol && (
                          <ButtonLink size="sm" to={`/enrol?employee=${w.id}`}>
                            Re-enrol
                          </ButtonLink>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollArea>
          ) : (
            <EmptyState
              icon="checkCircle"
              title="No one stands out"
              text="Everyone is recognised with good confidence."
            />
          )}
        </Card>
        <Card title="What reviewers decided" bodyClassName="chart">
          <HBarChart
            items={report.decisions.map(d => ({
              label: d.label,
              value: d.count,
              ...(d.outcome === 'EXPIRED' ? { color: 'var(--grey-line)' } : {}),
            }))}
          />
        </Card>
      </div>
    </>
  );
}

function DayByDay({ days }: { days: readonly RecognitionDay[] }) {
  return (
    <Card title="Day by day" className="mb-16" flush>
      <ScrollArea className="dt-wrap" label="Day by day">
        <table className="dt">
          <thead>
            <tr>
              <th>Day</th>
              <th className="num">Crossings</th>
              <th className="num">Auto-decided</th>
              <th className="num">Review</th>
              <th className="num">Re-ID share of OUT</th>
              <th className="num">Unknown faces</th>
              <th className="num">Liveness alerts</th>
              <th className="num">Duplicates ignored</th>
              <th className="num">Camera uptime</th>
              <th className="num">Avg review wait</th>
            </tr>
          </thead>
          <tbody>
            {days.map(d => (
              <tr key={d.day}>
                <td className="nowrap">{fmtDay(d.day)}</td>
                <td className="num">{d.crossings}</td>
                <td className="num">{pct(d.autoDecided, d.crossings)}</td>
                <td className="num">{pct(d.reviewed, d.crossings)}</td>
                <td className="num">{pct(d.reidOuts, d.outs)}</td>
                <td className="num">{d.unknownFaces}</td>
                <td className="num">{d.livenessAlerts}</td>
                <td className="num">{d.duplicatesIgnored}</td>
                <td className="num">{d.uptimePct === null ? '—' : `${String(d.uptimePct)}%`}</td>
                <td className="num">{d.reviewWaitMinutes === null ? '—' : fmtDuration(d.reviewWaitMinutes)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollArea>
    </Card>
  );
}
