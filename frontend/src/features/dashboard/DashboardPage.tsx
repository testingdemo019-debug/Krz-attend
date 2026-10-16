/**
 * The Dashboard (F8, the prototype's dashboardBodyHTML): KPIs, then two columns — camera, crossings by hour, recent
 * crossings | alerts, review summary, enrolment queue, system health — each section only with its permission. Data
 * from GET /dashboard, refreshed by realtime events.
 */
import { useDashboard, type Dashboard, type DashboardAlert } from '@/api/dashboard';
import { useMeta } from '@/api/me';
import { InOutChart } from '@/components/charts/InOutChart';
import { Legend } from '@/components/charts/Legend';
import { FeedItem } from '@/components/data/Feed';
import { KpiCard } from '@/components/data/KpiCard';
import { CameraTile, cameraPillState } from '@/components/domain/CameraTile';
import { FaceStatusChip } from '@/components/domain/Chips';
import { EventFeedItem } from '@/components/domain/EventFeedItem';
import { HealthRows } from '@/components/domain/HealthRow';
import { EmptyState, ErrorState } from '@/components/feedback/EmptyState';
import { Icon } from '@/components/icons/Icon';
import { Card, CardBody } from '@/components/layout/Card';
import { PageHead } from '@/components/layout/PageHead';
import { Avatar } from '@/components/ui/Avatar';
import { Badge } from '@/components/ui/Badge';
import { ButtonLink } from '@/components/ui/Button';
import { Chip, type ChipTone } from '@/components/ui/Chip';
import { LivePill } from '@/components/ui/LivePill';
import { fmtDay, fmtTime, greeting, hourLabel, relTime, todayDay, zonedParts, zoneLabel } from '@/lib/format';
import { readOverlayPrefs } from '@/lib/overlays';
import { useMeUser, usePermissionCheck } from '@/lib/permissions';
import { useNow } from '@/lib/useNow';
import { DIRECTION_MODES, REVIEW_TYPES, term } from '@/lib/vocabulary';
import type { IconName } from '@/components/icons/Icon';

const ALERT_ICONS: Record<string, IconName> = {
  CAMERA_OFFLINE: 'wifiOff',
  CAMERA_RECONNECTING: 'wifiOff',
  LIVENESS_WAITING: 'shieldAlert',
  REVIEW_WAITING: 'hourglass',
  HRMS_FAILED: 'alertCircle',
  PUNCHES_BLOCKED: 'alert',
  MISSING_CHECK_OUTS: 'calendarCheck',
  ACCESS_REQUESTS: 'userPlus',
  REENROLMENT: 'refresh',
};
const ALERT_TONES: Record<string, ChipTone> = { BAD: 'red', WARN: 'amber', INFO: 'blue' };

export function DashboardPage() {
  const me = useMeUser();
  const can = usePermissionCheck();
  const meta = useMeta(true).data;
  const dashboard = useDashboard();
  const now = useNow(60_000);
  const first = me.name.split(/\s+/)[0] ?? me.name;
  return (
    <>
      <PageHead
        title={`${greeting(now)}, ${first}`}
        sub={`${fmtDay(todayDay(now))} · ${meta?.orgName ?? ''} · times in ${zoneLabel()}`}
        actions={
          <>
            <Chip tone="green" icon="activity">
              Live · updates automatically
            </Chip>
            {can('attendance.view.all') && <ButtonLink to="/attendance/today">Today</ButtonLink>}
            {can('review.resolve') && (
              <ButtonLink variant="primary" icon="review" to="/review">
                Review Queue
              </ButtonLink>
            )}
            {can('people.enrol') && !can('attendance.view.all') && (
              <ButtonLink variant="primary" icon="scanface" to="/enrol">
                Start enrolment
              </ButtonLink>
            )}
          </>
        }
      />
      <div id="dash-body">
        {dashboard.isError ? (
          <ErrorState what="the dashboard" onRetry={() => void dashboard.refetch()} />
        ) : (
          dashboard.data && <Body data={dashboard.data} now={now} />
        )}
      </div>
    </>
  );
}

function Body({ data, now }: { data: Dashboard; now: number }) {
  const can = usePermissionCheck();
  const attendance = can('attendance.view.all');
  return (
    <>
      {data.kpis && <Kpis kpis={data.kpis} />}
      <div className="dash-grid">
        <div className="col gap-16">
          {data.camera && <CameraCard card={data.camera} />}
          {data.chart && <CrossingsChart chart={data.chart} now={now} />}
          {data.enrolment && !attendance && <EnrolmentCard card={data.enrolment} />}
          <RecentCrossings rows={data.recent} />
        </div>
        <div className="col gap-16">
          <Alerts alerts={data.alerts} />
          {data.review && !attendance && <ReviewSummary open={data.review} />}
          {data.enrolment && attendance && <EnrolmentCard card={data.enrolment} />}
          <Card title="System health">
            <HealthRows rows={data.health.rows} />
          </Card>
        </div>
      </div>
    </>
  );
}

function Kpis({ kpis: k }: { kpis: NonNullable<Dashboard['kpis']> }) {
  const notIn = k.notYetIn + k.absent;
  return (
    <div className="kpis">
      <KpiCard
        label="Inside now"
        value={k.inside}
        foot={`of ${k.working} working today`}
        icon="moveIn"
        tone="green"
        to="/attendance/today?filter=inside"
      />
      <KpiCard
        label="Checked in"
        value={k.checkedIn}
        foot="first IN of the shift day"
        icon="checkCircle"
        tone="green"
        to="/attendance/today?filter=checked-in"
      />
      <KpiCard
        label="Late"
        value={k.late}
        foot="after shift start + grace"
        icon="clock"
        tone="amber"
        to="/attendance/today?filter=late"
      />
      <KpiCard
        label={k.absent && !k.notYetIn ? 'Not yet in — counted as Absent' : 'Not yet in'}
        value={notIn}
        foot={
          k.absent
            ? `${k.absent} counted as Absent after the cut-off`
            : `becomes Absent ${k.absentCutoffHours} h after shift start`
        }
        icon="hourglass"
        tone={k.absent ? 'red' : 'grey'}
        to="/attendance/today?filter=not-in"
      />
      <KpiCard
        label="Checked out"
        value={k.checkedOut}
        foot="tentative until day close"
        icon="moveOut"
        tone="blue"
        to="/attendance/today?filter=checked-out"
      />
      <KpiCard
        label="Early exits"
        value={k.earlyExit}
        foot="final check-out before shift end"
        icon="logout"
        tone="amber"
        to="/attendance/today?filter=early-exit"
      />
      {k.pendingReview !== null && (
        <KpiCard
          label="Pending review"
          value={k.pendingReview}
          foot="every type"
          icon="review"
          tone={k.pendingReview ? 'purple' : 'grey'}
          to="/review"
        />
      )}
      {k.unknownFacesToday !== null && (
        <KpiCard
          label="Unknown faces today"
          value={k.unknownFacesToday}
          foot="clusters seen today"
          icon="user"
          tone="grey"
          to="/review?tab=unknown"
        />
      )}
      {k.hrmsFailed !== null && (
        <KpiCard
          label="HRMS failed"
          value={k.hrmsFailed}
          foot={k.hrmsFailed ? 'retries exhausted' : 'all pushes delivered'}
          icon="alertCircle"
          tone={k.hrmsFailed ? 'red' : 'green'}
          to="/settings/hrms"
        />
      )}
    </div>
  );
}

function CameraCard({ card }: { card: NonNullable<Dashboard['camera']> }) {
  const overlays = { ...readOverlayPrefs().global, names: true };
  return (
    <Card
      title={`${card.camera.name} camera`}
      actions={
        <ButtonLink size="sm" to="/live">
          Open Live View
        </ButtonLink>
      }
    >
      <CameraTile camera={card.camera} overlays={overlays} style={{ border: 0 }} />
      <div className="row wrap gap-16 mt-12 small">
        <span>
          <LivePill state={cameraPillState(card.camera)} />
        </span>
        <span>{term(DIRECTION_MODES, card.camera.directionMode)?.label}</span>
        {card.insideNow !== null && (
          <span>
            <b>{card.insideNow}</b> inside now
          </span>
        )}
        <span className="muted">Last crossing {card.lastCrossingAt ? fmtTime(card.lastCrossingAt) : '—'}</span>
      </div>
    </Card>
  );
}

function CrossingsChart({ chart, now }: { chart: NonNullable<Dashboard['chart']>; now: number }) {
  const labels = chart.inToday.map((_, i) => hourLabel(chart.firstHour + i));
  const nowIndex = zonedParts(now).h - chart.firstHour;
  return (
    <Card
      title="Crossings by hour — IN and OUT"
      bodyClassName="chart"
      actions={
        <Legend
          entries={[
            { label: 'IN today', colour: 'var(--green-line)' },
            { label: 'OUT today', colour: 'var(--accent)' },
            { label: `- - average of the last ${chart.averageDays} working days` },
          ]}
        />
      }
    >
      <InOutChart
        labels={labels}
        inBars={chart.inToday}
        outBars={chart.outToday}
        inAvg={chart.inAverage}
        outAvg={chart.outAverage}
        markers={nowIndex >= 0 && nowIndex < labels.length ? [{ index: nowIndex, label: 'now' }] : []}
      />
      <p className="help mt-4">
        Identified crossings at the entrance (U-turns and voided crossings excluded), 6 AM to midnight {zoneLabel()}.
      </p>
    </Card>
  );
}

function RecentCrossings({ rows }: { rows: Dashboard['recent'] }) {
  const can = usePermissionCheck();
  return (
    <Card
      title="Recent crossings"
      flush
      bodyClassName="feed"
      actions={
        can('events.view') ? (
          <ButtonLink size="sm" to="/events">
            All events
          </ButtonLink>
        ) : undefined
      }
    >
      {rows.length ? (
        rows.map(row => <EventFeedItem key={row.code} row={row} />)
      ) : (
        <CardBody className="muted small">No crossings yet today.</CardBody>
      )}
    </Card>
  );
}

function AlertRow({ alert }: { alert: DashboardAlert }) {
  const tone = ALERT_TONES[alert.level] ?? 'blue';
  return (
    <FeedItem to={alert.link}>
      <span
        className="kpi-ic"
        style={{
          position: 'static',
          width: 30,
          height: 30,
          borderRadius: 8,
          display: 'grid',
          placeItems: 'center',
          background: `var(--${tone}-bg)`,
          color: `var(--${tone})`,
        }}
      >
        <Icon name={ALERT_ICONS[alert.kind] ?? 'alert'} size="sm" />
      </span>
      <span className="grow">
        <b>{alert.title}</b>
        <br />
        <span className="feed-meta">{alert.at ? `${alert.sub} ${relTime(alert.at)}` : alert.sub}</span>
      </span>
      <Icon name="chevRight" size="sm" />
    </FeedItem>
  );
}

function Alerts({ alerts }: { alerts: Dashboard['alerts'] }) {
  return (
    <Card title="Alerts" flush actions={alerts.length ? <Badge count={alerts.length} /> : undefined}>
      {alerts.length ? (
        alerts.map(alert => <AlertRow key={`${alert.kind}-${alert.title}`} alert={alert} />)
      ) : (
        <CardBody>
          <EmptyState icon="checkCircle" title="No alerts" text="Everything looks normal." />
        </CardBody>
      )}
    </Card>
  );
}

function ReviewSummary({ open }: { open: NonNullable<Dashboard['review']> }) {
  return (
    <Card
      title="Review Queue"
      flush
      actions={
        <ButtonLink size="sm" to="/review">
          Open
        </ButtonLink>
      }
    >
      {Object.entries(REVIEW_TYPES).map(([type, t]) => {
        const n = open[type] ?? 0;
        return (
          <FeedItem key={type} to={`/review?tab=${type.toLowerCase()}`}>
            <Icon name={t.icon} />
            <span className="grow">{t.label}</span>
            <Badge count={n} {...(n ? {} : { tone: 'neutral' as const })} />
          </FeedItem>
        );
      })}
    </Card>
  );
}

function EnrolmentCard({ card }: { card: NonNullable<Dashboard['enrolment']> }) {
  return (
    <div className="card">
      <div className="card-head">
        <h2>Enrolment queue</h2>
        <Badge count={card.total} tone="neutral" />
      </div>
      <CardBody flush>
        {card.waiting.length ? (
          card.waiting.map(row => (
            <FeedItem key={row.employee.id} to={`/enrol?employee=${row.employee.id}`}>
              <Avatar id={row.employee.id} name={row.employee.name} size="sm" />
              <span className="grow">
                <b>{row.employee.name}</b>
                <br />
                <span className="feed-meta">
                  {[row.employee.department, row.employee.designation].filter(Boolean).join(' · ')}
                  {row.tracked && row.faceStatus === 'NOT_ENROLLED' ? ' · arrivals appear as unknown faces' : ''}
                </span>
              </span>
              <FaceStatusChip status={row.faceStatus} />
            </FeedItem>
          ))
        ) : (
          <CardBody>
            <EmptyState
              icon="checkCircle"
              title="Nobody waiting"
              text="Everyone at a location with a camera is enrolled."
            />
          </CardBody>
        )}
      </CardBody>
      <CardBody style={{ borderTop: '1px solid var(--border)' }}>
        <div className="row between">
          <span className="small">
            <b>{card.enrolledLast7Days}</b> enrolled or re-enrolled in the last 7 days
          </span>
          <ButtonLink size="sm" variant="primary" icon="scanface" to="/enrol">
            Start enrolment
          </ButtonLink>
        </div>
      </CardBody>
    </div>
  );
}
