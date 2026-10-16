import type { ReactNode } from 'react';
import './PageHead.module.css';

/** The page title row: h1, the grey sub-line and the actions on the right (wrapping on narrow screens). */
export function PageHead({ title, sub, actions }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {sub !== undefined && <div className="sub">{sub}</div>}
      </div>
      {actions !== undefined && <div className="page-actions">{actions}</div>}
    </div>
  );
}
