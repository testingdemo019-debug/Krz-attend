import { cx } from '@/lib/cx';
import './Skeleton.module.css';
import '@/components/data/DataTable.module.css';

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
