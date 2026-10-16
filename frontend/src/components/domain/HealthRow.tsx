import type { StatusRow } from '@/api/shell';
import { STATUS_LEVEL } from '@/lib/vocabulary';
import './HealthRow.module.css';

/** One service in the status lists (the prototype's statusRowsHTML): dot, label, muted detail and the state. */
export function HealthRow({ row }: { row: StatusRow }) {
  return (
    <div className="health-row">
      <span className={`sdot ${STATUS_LEVEL[row.level]}`} />
      <span className="grow">
        {row.label}
        {row.detail && (
          <>
            {' '}
            <span className="muted xs">{row.detail}</span>
          </>
        )}
      </span>
      <span className="small muted">{row.text}</span>
    </div>
  );
}

export function HealthRows({ rows }: { rows: readonly StatusRow[] }) {
  return (
    <>
      {rows.map(row => (
        <HealthRow key={row.key} row={row} />
      ))}
    </>
  );
}
