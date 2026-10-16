import { Icon, type IconName } from '@/components/icons/Icon';
import { cx } from '@/lib/cx';
import { Badge } from './Badge';
import './Tabs.module.css';

export interface TabItem<K extends string> {
  key: K;
  label: string;
  icon?: IconName;
  /** A count badge after the label. */
  count?: number;
}

/** The prototype's .tabs strip: one button per tab, the active one underlined, with optional count badges. */
export function Tabs<K extends string>({
  items,
  active,
  onSelect,
  label,
}: {
  items: readonly TabItem<K>[];
  active: K;
  onSelect: (key: K) => void;
  label: string;
}) {
  return (
    <div className="tabs" role="tablist" aria-label={label}>
      {items.map(item => (
        <button
          key={item.key}
          type="button"
          className={cx('tab', item.key === active && 'active')}
          role="tab"
          aria-selected={item.key === active}
          onClick={() => {
            onSelect(item.key);
          }}
        >
          {item.icon && <Icon name={item.icon} size="sm" />}
          {item.label}
          {item.count !== undefined && <Badge count={item.count} />}
        </button>
      ))}
    </div>
  );
}
