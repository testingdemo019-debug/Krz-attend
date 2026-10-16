import type { ReactNode } from 'react';
import { Icon, type IconName } from '@/components/icons/Icon';
import { cx } from '@/lib/cx';
import './Segmented.module.css';

export interface Segment<T extends string> {
  value: T;
  label: ReactNode;
  icon?: IconName;
}

/** The prototype's .seg: toggle buttons in one bordered group; the chosen one is "on" and aria-pressed. */
export function Segmented<T extends string>({
  segments,
  value,
  onChange,
  label,
  size,
}: {
  segments: readonly Segment<T>[];
  value: T;
  onChange: (value: T) => void;
  label: string;
  size?: 'sm';
}) {
  return (
    <div className={cx('seg', size)} role="group" aria-label={label}>
      {segments.map(s => (
        <button
          key={s.value}
          type="button"
          className={s.value === value ? 'on' : undefined}
          aria-pressed={s.value === value}
          onClick={() => {
            onChange(s.value);
          }}
        >
          {s.icon && <Icon name={s.icon} size="sm" />}
          {s.label}
        </button>
      ))}
    </div>
  );
}
