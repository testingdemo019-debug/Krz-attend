/**
 * The prototype's dataTable for server-side lists (F3, F10): search, filter popovers (options with counts, date
 * ranges with presets), sortable columns, column chooser, compact rows, CSV export from the server, paging, and the
 * loading, error and empty states. The state lives in the URL (tableState.ts); the page turns it into its query.
 */
import type { UseQueryResult } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { DateField } from '@/components/form/DateField';
import { EmptyState, ErrorState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Icon, type IconName } from '@/components/icons/Icon';
import { Button, DownloadButton } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { MenuItem, MenuLabel, MenuSeparator, Popover } from '@/components/ui/Popover';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { toggleCompactRows, useCompactRows } from '@/lib/compact';
import { cx } from '@/lib/cx';
import { addDays, fmtDateShort, todayDay } from '@/lib/format';
import { useCan, useNeedsPermissionText, usePermissionCheck, type PermissionKey } from '@/lib/permissions';
import { readPreference, writePreference } from '@/lib/storage';
import { fmtCount } from '@/lib/text';
import {
  anyFilter,
  isRange,
  PAGE_SIZES,
  type DayRange,
  type OptionFilter,
  type RangeFilter,
  type TableControl,
  type TableFilter,
} from './tableState';
import './DataTable.module.css';

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Column<T> {
  key: string;
  label: string;
  render: (row: T) => ReactNode;
  /** The API's sort key; the header sorts when set. */
  sort?: string;
  num?: boolean;
  className?: string;
  /** Hidden until chosen in the column chooser (e.g. Events' Event ID). */
  hidden?: boolean;
  /** Always shown: the chooser cannot hide it (the Employee column). */
  lock?: boolean;
  width?: string;
}

/** The column chooser's choices, kept per table: columns shown or hidden against their default. */
interface ColumnChoice {
  hide: readonly string[];
  show: readonly string[];
}

/** A bulk action on the selected rows (the prototype's bulkActions). */
export interface BulkAction {
  label: string;
  /** Without it the button is disabled with the "needs …" tooltip. */
  permission?: PermissionKey;
  /** `done` clears the selection. */
  run: (keys: readonly string[], done: () => void) => void;
}

export interface TableExport {
  /** The server export URL with the table's filters. */
  href: string;
  /** The file name the server gives it, for the toast. */
  fileName: string;
  permission: PermissionKey;
}

const DEFAULT_PRESETS: readonly (readonly [string, number, number])[] = [
  ['Today', 0, 0],
  ['Last 7 days', -6, 0],
  ['Last 14 days', -13, 0],
];

function filterLabel(filter: TableFilter, control: TableControl): ReactNode {
  if (isRange(filter)) {
    const r = control.state.ranges[filter.key];
    if (!r || (r.from === '' && r.to === '')) return filter.label;
    return `${filter.label}: ${r.from ? fmtDateShort(r.from) : '…'} – ${r.to ? fmtDateShort(r.to) : '…'}`;
  }
  const values = control.state.values[filter.key] ?? [];
  const firstValue = values[0];
  if (firstValue === undefined) return filter.label;
  const first = filter.options.find(o => o.value === firstValue);
  return `${filter.label}: ${first ? first.label : firstValue}${values.length > 1 ? ` +${String(values.length - 1)}` : ''}`;
}

function isOn(filter: TableFilter, control: TableControl): boolean {
  if (isRange(filter)) {
    const r = control.state.ranges[filter.key];
    return Boolean(r && (r.from || r.to));
  }
  return (control.state.values[filter.key] ?? []).length > 0;
}

export function DataTable<T>({
  id,
  control,
  filters = [],
  columns,
  rowKey,
  result,
  noun,
  noun1,
  empty,
  searchPlaceholder = 'Search…',
  exportCsv,
  onRow,
  footNote,
  bulkActions,
}: {
  /** Names the column preference in storage. */
  id: string;
  control: TableControl;
  filters?: readonly TableFilter[];
  columns: readonly Column<T>[];
  rowKey: (row: T) => string;
  result: UseQueryResult<Page<T>>;
  noun: string;
  noun1: string;
  empty: { icon: IconName; title: string; text?: string; action?: ReactNode };
  searchPlaceholder?: string;
  exportCsv?: TableExport;
  /** Opens the row's detail (click, or Enter on the focused row). */
  onRow?: (row: T) => void;
  /** After the count in the footer (the log's date range). */
  footNote?: string;
  /** Rows can be selected (a checkbox column) for these actions. */
  bulkActions?: readonly BulkAction[];
}) {
  const { state } = control;
  const can = usePermissionCheck();
  const needs = useNeedsPermissionText();
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const selectable = bulkActions !== undefined;
  const toggle = (key: string): void => {
    setSelected(current => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };
  const [choice, setChoice] = useState<ColumnChoice>(() =>
    readPreference<ColumnChoice>(`columns.${id}`, { hide: [], show: [] }),
  );
  const hidden = columns
    .filter(c => (c.hidden === true && !choice.show.includes(c.key)) || choice.hide.includes(c.key))
    .map(c => c.key);
  const visible = columns.filter(c => !hidden.includes(c.key));
  const total = result.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / state.size));

  // Rows were removed elsewhere and this page is past the end: show the last page.
  useEffect(() => {
    if (result.data && state.page > pages) control.set({ page: pages });
  }, [result.data, state.page, pages, control]);

  const toggleColumn = (key: string): void => {
    const show = hidden.includes(key);
    const next: ColumnChoice = {
      hide: show ? choice.hide.filter(k => k !== key) : [...choice.hide, key],
      show: show ? [...choice.show, key] : choice.show.filter(k => k !== key),
    };
    setChoice(next);
    writePreference(`columns.${id}`, next);
  };

  return (
    <div className="dt-card">
      <Toolbar
        control={control}
        filters={filters}
        columns={columns}
        hidden={hidden}
        onToggleColumn={toggleColumn}
        searchPlaceholder={searchPlaceholder}
        total={total}
        exportCsv={exportCsv}
      />
      <div className="dt-bodywrap">
        {result.isPending ? (
          <div className="dt-wrap">
            <SkeletonRows rows={8} columns={Math.min(6, columns.length)} />
          </div>
        ) : result.isError ? (
          <ErrorState
            what={noun}
            onRetry={() => {
              void result.refetch();
            }}
          />
        ) : total === 0 ? (
          state.q !== '' || anyFilter(state) ? (
            <EmptyState
              icon="search"
              title="No matches"
              text="Nothing matches the current search or filters."
              action={<Button onClick={control.clear}>Clear search and filters</Button>}
            />
          ) : (
            <EmptyState
              icon={empty.icon}
              title={empty.title}
              {...(empty.text ? { text: empty.text } : {})}
              {...(empty.action ? { action: empty.action } : {})}
            />
          )
        ) : (
          <>
            {selectable && selected.size > 0 && (
              <div className="bulkbar">
                {selected.size} selected
                <span className="grow" />
                {bulkActions.map(b =>
                  b.permission && !can(b.permission) ? (
                    <Button key={b.label} size="sm" disabled tooltip={needs(b.permission)}>
                      {b.label}
                    </Button>
                  ) : (
                    <Button
                      key={b.label}
                      size="sm"
                      onClick={() => {
                        b.run([...selected], () => {
                          setSelected(new Set());
                        });
                      }}
                    >
                      {b.label}
                    </Button>
                  ),
                )}
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setSelected(new Set());
                  }}
                >
                  Clear selection
                </Button>
              </div>
            )}
            <ScrollArea className="dt-wrap" label={noun}>
              <table className="dt">
                <thead>
                  <tr>
                    {selectable && (
                      <th style={{ width: 36 }}>
                        <input
                          type="checkbox"
                          aria-label="Select all on page"
                          checked={
                            result.data.items.length > 0 && result.data.items.every(r => selected.has(rowKey(r)))
                          }
                          onChange={() => {
                            const keys = result.data.items.map(rowKey);
                            const all = keys.every(k => selected.has(k));
                            setSelected(current => {
                              const next = new Set(current);
                              for (const k of keys) {
                                if (all) next.delete(k);
                                else next.add(k);
                              }
                              return next;
                            });
                          }}
                        />
                      </th>
                    )}
                    {visible.map(c => (
                      <HeaderCell key={c.key} column={c} control={control} />
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.data.items.map(row => {
                    const key = rowKey(row);
                    const cells = (
                      <>
                        {selectable && (
                          <td>
                            <input
                              type="checkbox"
                              aria-label="Select row"
                              checked={selected.has(key)}
                              onChange={() => {
                                toggle(key);
                              }}
                            />
                          </td>
                        )}
                        {visible.map(c => (
                          <td key={c.key} className={cx(c.num && 'num', c.className)}>
                            {c.render(row)}
                          </td>
                        ))}
                      </>
                    );
                    return onRow ? (
                      // The prototype's t-row: the whole row opens the detail; buttons and links in it keep their own.
                      <tr
                        key={key}
                        className={cx('clickable', selected.has(key) && 'selected')}
                        tabIndex={0}
                        onClick={event => {
                          if (!(event.target as Element).closest('button,a,input,label,select')) onRow(row);
                        }}
                        onKeyDown={event => {
                          if (event.key === 'Enter' && event.target === event.currentTarget) onRow(row);
                        }}
                      >
                        {cells}
                      </tr>
                    ) : (
                      <tr key={key} className={cx(selected.has(key) && 'selected')}>
                        {cells}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </ScrollArea>
            <Footer
              control={control}
              total={total}
              pages={pages}
              noun={total === 1 ? noun1 : noun}
              id={id}
              note={footNote}
            />
          </>
        )}
      </div>
    </div>
  );
}

function HeaderCell<T>({ column: c, control }: { column: Column<T>; control: TableControl }) {
  const sort = control.state.sort;
  const sorted = c.sort !== undefined && sort?.key === c.sort;
  const width = c.width ? { width: c.width } : undefined;
  if (c.sort === undefined)
    return (
      <th className={cx(c.num && 'num')} style={width}>
        {c.label}
      </th>
    );
  const key = c.sort;
  const toggle = (): void => {
    control.set({ sort: sorted && sort.dir === 'asc' ? { key, dir: 'desc' } : { key, dir: 'asc' } });
  };
  return (
    <th
      className={cx('sortable', sorted && 'sorted', c.num && 'num')}
      style={width}
      tabIndex={0}
      aria-sort={sorted ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
      onClick={toggle}
      onKeyDown={event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          toggle();
        }
      }}
    >
      {c.label}
      <Icon name={sorted ? (sort.dir === 'asc' ? 'chevUp' : 'chevDown') : 'sort'} size="sm" className="sort-ic" />
    </th>
  );
}

function Toolbar<T>({
  control,
  filters,
  columns,
  hidden,
  onToggleColumn,
  searchPlaceholder,
  total,
  exportCsv,
}: {
  control: TableControl;
  filters: readonly TableFilter[];
  columns: readonly Column<T>[];
  hidden: readonly string[];
  onToggleColumn: (key: string) => void;
  searchPlaceholder: string;
  total: number;
  exportCsv: TableExport | undefined;
}) {
  const compact = useCompactRows();
  const canExport = useCan(exportCsv?.permission ?? 'events.export');
  const needs = useNeedsPermissionText();
  return (
    <div className="dt-toolbar">
      <SearchBox control={control} placeholder={searchPlaceholder} />
      <span className="dt-filters row wrap gap-4">
        {filters.map(f => (
          <FilterButton key={f.key} filter={f} control={control} />
        ))}
        {anyFilter(control.state) && (
          <Button variant="ghost" size="sm" onClick={control.clear}>
            Clear filters
          </Button>
        )}
      </span>
      <span className="grow" />
      <IconButton
        icon="menu"
        label="Compact rows"
        tooltip="Compact rows"
        bordered
        active={compact}
        aria-pressed={compact}
        onClick={toggleCompactRows}
      />
      <Popover
        align="right"
        label="Visible columns"
        trigger={<IconButton icon="columns" label="Choose columns" tooltip="Columns" bordered />}
      >
        <MenuLabel>Visible columns</MenuLabel>
        {columns.map(c => (
          <label key={c.key} className="menu-item check">
            <input
              type="checkbox"
              checked={!hidden.includes(c.key)}
              disabled={c.lock}
              onChange={() => {
                onToggleColumn(c.key);
              }}
            />
            {c.label}
          </label>
        ))}
      </Popover>
      {exportCsv &&
        (canExport ? (
          <DownloadButton
            size="sm"
            icon="download"
            href={exportCsv.href}
            onClick={() => {
              toast(`Exported ${fmtCount(total)} rows to ${exportCsv.fileName}`, { type: 'success', logged: true });
            }}
          >
            Export CSV
          </DownloadButton>
        ) : (
          <Button size="sm" icon="download" disabled tooltip={needs(exportCsv.permission)}>
            Export CSV
          </Button>
        ))}
    </div>
  );
}

/** The search field: typing waits a moment before it changes the query. */
function SearchBox({ control, placeholder }: { control: TableControl; placeholder: string }) {
  const [draft, setDraft] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  return (
    <div className="dt-search">
      <Icon name="search" size="sm" />
      <input
        className="input"
        type="search"
        placeholder={placeholder}
        aria-label="Search table"
        value={draft ?? control.state.q}
        onChange={event => {
          const text = event.target.value;
          setDraft(text);
          if (timer.current) clearTimeout(timer.current);
          timer.current = setTimeout(() => {
            control.set({ q: text });
            setDraft(null);
          }, 250);
        }}
      />
    </div>
  );
}

function FilterButton({ filter, control }: { filter: TableFilter; control: TableControl }) {
  const [open, setOpen] = useState(false);
  const on = isOn(filter, control);
  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      label={filter.label}
      trigger={
        <button type="button" className={cx('filter-btn', on && 'on')} aria-haspopup="true">
          <Icon name={on ? 'filter' : 'plus'} size="sm" />
          {filterLabel(filter, control)}
        </button>
      }
    >
      {isRange(filter) ? (
        <RangeEditor
          filter={filter}
          control={control}
          onDone={() => {
            setOpen(false);
          }}
        />
      ) : (
        <OptionsEditor
          filter={filter}
          control={control}
          onDone={() => {
            setOpen(false);
          }}
        />
      )}
    </Popover>
  );
}

function OptionsEditor({
  filter,
  control,
  onDone,
}: {
  filter: OptionFilter;
  control: TableControl;
  onDone: () => void;
}) {
  const [find, setFind] = useState('');
  const selected = control.state.values[filter.key] ?? [];
  const shown = filter.options.filter(o => o.label.toLowerCase().includes(find.toLowerCase()));
  return (
    <>
      <MenuLabel>{filter.label}</MenuLabel>
      {filter.options.length > 8 && (
        <div style={{ padding: '4px 6px' }}>
          <input
            className="input"
            placeholder="Filter options…"
            aria-label={`Filter ${filter.label} options`}
            value={find}
            onChange={event => {
              setFind(event.target.value);
            }}
          />
        </div>
      )}
      <div>
        {shown.map(o => (
          <label key={o.value} className="menu-item check">
            <input
              type="checkbox"
              checked={selected.includes(o.value)}
              onChange={event => {
                const next = event.target.checked ? [...selected, o.value] : selected.filter(v => v !== o.value);
                control.set({ values: { ...control.state.values, [filter.key]: next } });
              }}
            />
            <span className="grow">{o.label}</span>
            {o.count !== undefined && <span className="muted xs num">{o.count}</span>}
          </label>
        ))}
      </div>
      <MenuSeparator />
      <MenuItem
        onClick={() => {
          control.set({ values: { ...control.state.values, [filter.key]: [] } });
          onDone();
        }}
      >
        Clear
      </MenuItem>
    </>
  );
}

function RangeEditor({ filter, control, onDone }: { filter: RangeFilter; control: TableControl; onDone: () => void }) {
  const [range, setRange] = useState<DayRange>(() => control.state.ranges[filter.key] ?? { from: '', to: '' });
  const id = useId();
  const apply = (next: DayRange): void => {
    control.set({ ranges: { ...control.state.ranges, [filter.key]: next } });
    onDone();
  };
  return (
    <div className="col" style={{ padding: 6, minWidth: 250 }}>
      <div className="field">
        <label htmlFor={`${id}-from`}>From</label>
        <DateField
          id={`${id}-from`}
          value={range.from}
          label="From date"
          onChange={from => {
            setRange(r => ({ ...r, from }));
          }}
        />
      </div>
      <div className="field">
        <label htmlFor={`${id}-to`}>To</label>
        <DateField
          id={`${id}-to`}
          value={range.to}
          label="To date"
          onChange={to => {
            setRange(r => ({ ...r, to }));
          }}
        />
      </div>
      <div className="row wrap gap-4">
        {DEFAULT_PRESETS.map(([label, a, b]) => (
          <Button
            key={label}
            size="sm"
            onClick={() => {
              const today = todayDay();
              setRange({ from: addDays(today, a), to: addDays(today, b) });
            }}
          >
            {label}
          </Button>
        ))}
      </div>
      <div className="row between">
        <Button
          size="sm"
          variant="ghost"
          onClick={() => {
            apply({ from: '', to: '' });
          }}
        >
          Clear
        </Button>
        <Button
          size="sm"
          variant="primary"
          onClick={() => {
            apply(range);
          }}
        >
          Apply
        </Button>
      </div>
    </div>
  );
}

function Footer({
  control,
  total,
  pages,
  noun,
  id,
  note,
}: {
  control: TableControl;
  total: number;
  pages: number;
  noun: string;
  id: string;
  note: string | undefined;
}) {
  const { page, size } = control.state;
  return (
    <div className="dt-footer">
      <span>
        {fmtCount(total)} {noun}
        {note ? ` · ${note}` : ''}
      </span>
      <div className="pager">
        <label className="small muted" htmlFor={`ps-${id}`}>
          Rows per page
        </label>
        <select
          id={`ps-${id}`}
          className="select"
          style={{ width: 76, height: 30 }}
          value={size}
          onChange={event => {
            control.set({ size: Number(event.target.value) });
          }}
        >
          {PAGE_SIZES.map(n => (
            <option key={n}>{n}</option>
          ))}
        </select>
        <span className="num">
          {(page - 1) * size + 1}–{Math.min(total, page * size)} of {total}
        </span>
        <IconButton
          icon="chevLeft"
          size="sm"
          bordered
          label="Previous page"
          disabled={page <= 1}
          onClick={() => {
            control.set({ page: page - 1 });
          }}
        />
        <IconButton
          icon="chevRight"
          size="sm"
          bordered
          label="Next page"
          disabled={page >= pages}
          onClick={() => {
            control.set({ page: page + 1 });
          }}
        />
      </div>
    </div>
  );
}
