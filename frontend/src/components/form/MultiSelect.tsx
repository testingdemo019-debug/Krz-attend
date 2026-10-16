/**
 * The prototype's multiSelectHTML: a searchable checkbox list with "Select all shown", "Clear" and the count (the
 * allocation wizard's departments or employees, the department editor's members).
 */
import { useState, type ReactNode } from 'react';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import '@/components/data/DataTable.module.css';
import './MultiSelect.module.css';

export interface MultiSelectItem {
  id: string;
  label: string;
  sub?: ReactNode;
  /** What the search matches (the label by default). */
  search?: string;
  disabled?: boolean;
}

export function MultiSelect({
  items,
  selected,
  onChange,
  searchPlaceholder = 'Search…',
  height = 300,
}: {
  items: readonly MultiSelectItem[];
  selected: ReadonlySet<string>;
  onChange: (next: Set<string>) => void;
  searchPlaceholder?: string;
  height?: number;
}) {
  const [q, setQ] = useState('');
  const needle = q.toLowerCase();
  const shown = items.filter(it => (it.search ?? it.label).toLowerCase().includes(needle));
  return (
    <div className="msel">
      <div className="row gap-8 mb-8">
        <div className="dt-search grow">
          <Icon name="search" size="sm" />
          <input
            className="input"
            placeholder={searchPlaceholder}
            aria-label={searchPlaceholder}
            value={q}
            onChange={event => {
              setQ(event.target.value);
            }}
          />
        </div>
        <Button
          size="sm"
          onClick={() => {
            const next = new Set(selected);
            for (const it of shown) if (!it.disabled) next.add(it.id);
            onChange(next);
          }}
        >
          Select all shown
        </Button>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => {
            onChange(new Set());
          }}
        >
          Clear
        </Button>
      </div>
      <div className="msel-list" style={{ maxHeight: height }} role="group">
        {items.length === 0 ? (
          <p className="muted small" style={{ padding: 8 }}>
            Nothing to choose.
          </p>
        ) : (
          items.map(it => (
            <label
              key={it.id}
              className={`msel-row ${it.disabled ? 'muted' : ''}`}
              style={shown.includes(it) ? undefined : { display: 'none' }}
            >
              <input
                type="checkbox"
                checked={selected.has(it.id)}
                disabled={it.disabled}
                onChange={event => {
                  const next = new Set(selected);
                  if (event.target.checked) next.add(it.id);
                  else next.delete(it.id);
                  onChange(next);
                }}
              />
              <span className="grow">
                {it.label}
                {it.sub !== undefined && (
                  <>
                    <br />
                    <span className="muted small">{it.sub}</span>
                  </>
                )}
              </span>
            </label>
          ))
        )}
      </div>
      <div className="muted small mt-8">{selected.size} selected</div>
    </div>
  );
}
