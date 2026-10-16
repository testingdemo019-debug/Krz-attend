import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { Icon, type IconName } from '@/components/icons/Icon';
import type { ChipTone } from '@/components/ui/Chip';
import { cx } from '@/lib/cx';
import './KpiCard.module.css';

interface KpiContent {
  label: string;
  value: ReactNode;
  foot?: ReactNode;
  icon: IconName;
  tone?: ChipTone;
}

function Body({ label, value, foot, icon, tone = 'grey' }: KpiContent) {
  return (
    <>
      <span className="kpi-label">{label}</span>
      <span className="kpi-ic" style={{ background: `var(--${tone}-bg)`, color: `var(--${tone})` }}>
        <Icon name={icon} size="sm" />
      </span>
      <div className="kpi-value">{value}</div>
      <div className="kpi-foot">{foot}</div>
    </>
  );
}

/** The prototype's kpiCard: a link to the list behind the number. */
export function KpiCard({ to, ...content }: KpiContent & { to: string }) {
  return (
    <Link className="kpi" to={to}>
      <Body {...content} />
    </Link>
  );
}

/** The clickable variant (Today's quick filters): a toggle button, "on" when its filter is applied. */
export function KpiButton({ on, onClick, ...content }: KpiContent & { on: boolean; onClick: () => void }) {
  return (
    <button type="button" className={cx('kpi', on && 'on')} aria-pressed={on} onClick={onClick}>
      <Body {...content} />
    </button>
  );
}
