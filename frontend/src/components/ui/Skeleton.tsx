import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';

export function Skeleton({ className, width, height }: { className?: string; width?: string; height?: string }) {
  return (
    <div
      className={cx('skeleton', className)}
      style={width || height ? { width, height } : undefined}
      aria-hidden="true"
    />
  );
}

/** The prototype's skeletonRows(n, cols): a table of shimmering lines while a list loads. */
export function SkeletonRows({ rows = 6, columns = 5 }: { rows?: number; columns?: number }) {
  return (
    <table className="dt" aria-busy="true" aria-label="Loading">
      <tbody>
        {Array.from({ length: rows }, (_, r) => (
          <tr key={r}>
            {Array.from({ length: columns }, (_, c) => (
              <td key={c}>
                <Skeleton className="sk-line" width={`${c === 0 ? 70 : 40 + ((c * 13) % 40)}%`} />
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/*
 * Page-shaped placeholders (D-121): each has the layout of what it stands for, so nothing jumps when the content
 * arrives. They fade in after 150 ms (a fast load shows no flicker) and do not shimmer with reduced motion.
 */

const range = (n: number): number[] => Array.from({ length: n }, (_, i) => i);
/** Steady, varied widths, so the lines look like text rather than a grid. */
const width = (i: number, from = 45, spread = 40): string => `${String(from + ((i * 23) % spread))}%`;

function SkeletonPage({ what, className, children }: { what: string; className?: string; children: ReactNode }) {
  return (
    <div className={cx('sk-page', className)} role="status" aria-busy="true" aria-label={`Loading ${what}`}>
      {children}
    </div>
  );
}

/** A table with its own column headings while its rows load (every DataTable list). */
export function TableSkeleton({
  columns,
  rows = 8,
}: {
  columns: readonly { key: string; label: ReactNode; num?: boolean | undefined }[];
  rows?: number;
}) {
  return (
    <SkeletonPage what="the list">
      <table className="dt">
        <thead>
          <tr>
            {columns.map(c => (
              <th key={c.key} className={cx(c.num && 'num')}>
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {range(rows).map(r => (
            <tr key={r}>
              {columns.map((c, i) => (
                <td key={c.key} className={cx(c.num && 'num')}>
                  {i === 0 ? (
                    <span className="row gap-8">
                      <Skeleton className="sk-circle" width="26px" height="26px" />
                      <Skeleton className="sk-line grow" width={width(r, 40, 30)} />
                    </span>
                  ) : (
                    <Skeleton className={cx('sk-line', c.num && 'sk-end')} width={c.num ? '40%' : width(r + i)} />
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </SkeletonPage>
  );
}

/** The KPI tiles row (Dashboard, reports). */
export function KpiSkeleton({ count = 6 }: { count?: number }) {
  return (
    <div className="kpis">
      {range(count).map(i => (
        <div key={i} className="kpi sk-kpi">
          <Skeleton className="sk-line" width="55%" />
          <Skeleton className="sk-value" width="38%" />
          <Skeleton className="sk-line" width={width(i, 50, 30)} />
          <Skeleton className="sk-kpi-ic" />
        </div>
      ))}
    </div>
  );
}

/** A card: its title, then a chart area or list rows. */
export function CardSkeleton({ chart = false, rows = 4 }: { chart?: boolean; rows?: number }) {
  return (
    <div className="card">
      <div className="card-head">
        <Skeleton className="sk-line" width="35%" />
      </div>
      <div className="card-body">
        {chart ? (
          <div className="sk-chart">
            {range(14).map(i => (
              <Skeleton key={i} className="sk-bar" height={`${String(25 + ((i * 37) % 70))}%`} />
            ))}
          </div>
        ) : (
          range(rows).map(i => (
            <div key={i} className="row gap-8 sk-list-row">
              <Skeleton className="sk-circle" width="28px" height="28px" />
              <div className="grow">
                <Skeleton className="sk-line" width={width(i)} />
                <Skeleton className="sk-line" width={width(i + 2, 25, 25)} />
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

/** The Dashboard: KPI tiles, then the camera and chart cards beside alerts and health. */
export function DashboardSkeleton() {
  return (
    <SkeletonPage what="the dashboard">
      <KpiSkeleton count={7} />
      <div className="dash-grid">
        <div className="col gap-16">
          <CardSkeleton chart />
          <CardSkeleton rows={5} />
        </div>
        <div className="col gap-16">
          <CardSkeleton rows={3} />
          <CardSkeleton rows={4} />
        </div>
      </div>
    </SkeletonPage>
  );
}

/** A report: KPI tiles, a chart, a table. */
export function ReportSkeleton({ kpis = 4 }: { kpis?: number }) {
  return (
    <SkeletonPage what="the report">
      <KpiSkeleton count={kpis} />
      <div className="col gap-16">
        <CardSkeleton chart />
        <CardSkeleton rows={6} />
      </div>
    </SkeletonPage>
  );
}

/** Live View: camera tiles with their picture (16:9) and status line. */
export function CameraTilesSkeleton({ count = 2 }: { count?: number }) {
  return (
    <SkeletonPage what="the cameras" className="live-grid l2">
      {range(count).map(i => (
        <div key={i} className="card sk-tile">
          <Skeleton className="sk-media" />
          <div className="row between sk-tile-foot">
            <Skeleton className="sk-line" width="40%" />
            <Skeleton className="sk-pill" />
          </div>
        </div>
      ))}
    </SkeletonPage>
  );
}

/** Review Queue cards: crops, the details, the action buttons. */
export function ReviewCardsSkeleton({ count = 3 }: { count?: number }) {
  return (
    <SkeletonPage what="the review items" className="rv-layout">
      {range(count).map(i => (
        <div key={i} className="rv-card">
          <div className="rv-crops">
            <Skeleton className="sk-line" width="60%" />
            <Skeleton className="sk-crop" />
          </div>
          <div>
            <Skeleton className="sk-line" width="45%" />
            <Skeleton className="sk-line" width="30%" />
            <div className="mt-12">
              {range(3).map(j => (
                <Skeleton key={j} className="sk-line" width={width(i + j, 55, 35)} />
              ))}
            </div>
          </div>
          <div className="rv-actions">
            {range(3).map(j => (
              <Skeleton key={j} className="sk-btn" />
            ))}
          </div>
        </div>
      ))}
    </SkeletonPage>
  );
}

/** A profile: avatar and name, the tabs, the first tab's cards. */
export function ProfileSkeleton() {
  return (
    <SkeletonPage what="the profile">
      <div className="card pad row gap-16">
        <Skeleton className="sk-circle" width="64px" height="64px" />
        <div className="grow">
          <Skeleton className="sk-line" width="28%" height="18px" />
          <Skeleton className="sk-line" width="45%" />
          <Skeleton className="sk-line" width="35%" />
        </div>
      </div>
      <div className="row gap-16 sk-tabs">
        {range(5).map(i => (
          <Skeleton key={i} className="sk-line" width="84px" />
        ))}
      </div>
      <div className="grid grid-3">
        {range(3).map(i => (
          <CardSkeleton key={i} rows={3} />
        ))}
      </div>
    </SkeletonPage>
  );
}

/** A detail drawer: the heading, the picture, then label and value rows. */
export function DrawerSkeleton({ rows = 7 }: { rows?: number }) {
  return (
    <SkeletonPage what="the details">
      <Skeleton className="sk-line" width="50%" height="16px" />
      <Skeleton className="sk-line" width="30%" />
      <Skeleton className="sk-media mt-12" />
      <div className="mt-16">
        {range(rows).map(i => (
          <div key={i} className="row gap-16 sk-kv">
            <Skeleton className="sk-line" width="28%" />
            <Skeleton className="sk-line grow" width={width(i, 35, 40)} />
          </div>
        ))}
      </div>
    </SkeletonPage>
  );
}

/** A settings page: a heading, then label and field pairs. */
export function FormSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <SkeletonPage what="the settings">
      <Skeleton className="sk-line" width="22%" height="16px" />
      <Skeleton className="sk-line" width="48%" />
      <div className="form-grid mt-16">
        {range(rows).map(i => (
          <div key={i}>
            <Skeleton className="sk-line" width={width(i, 30, 30)} />
            <Skeleton className="sk-field" />
          </div>
        ))}
      </div>
    </SkeletonPage>
  );
}
