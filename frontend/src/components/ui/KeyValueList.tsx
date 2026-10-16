import { Fragment, type ReactNode } from 'react';
import './KeyValueList.module.css';

export type KeyValue = readonly [label: string, value: ReactNode];

/** The prototype's keyValueList: a two-column <dl class="kv">; null entries are skipped. */
export function KeyValueList({ pairs }: { pairs: readonly (KeyValue | null | false)[] }) {
  return (
    <dl className="kv">
      {pairs
        .filter((pair): pair is KeyValue => Boolean(pair))
        .map(([label, value]) => (
          <Fragment key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </Fragment>
        ))}
    </dl>
  );
}
