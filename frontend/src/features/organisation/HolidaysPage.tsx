/**
 * Holidays (the prototype's #/org/holidays): public holidays turn a working day into "Holiday"; optional ones are
 * listed for reference. A year at a time (?year=), as a 12-month calendar with the year at a glance or as the table
 * (?view=list); a day or a row opens the editor; CSV import with the server's preview.
 */
import { useMemo, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { apiUrl } from '@/api/client';
import { useHolidayFacets, useHolidays, useHolidayYear, useShifts, type HolidayView } from '@/api/organisation';
import { Legend } from '@/components/charts/Legend';
import { DataTable, type Column } from '@/components/data/DataTable';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import { HrmsBanner } from '@/components/domain/HrmsBanner';
import { EmptyState } from '@/components/feedback/EmptyState';
import { PageHead } from '@/components/layout/PageHead';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip, Tag } from '@/components/ui/Chip';
import { IconButton } from '@/components/ui/IconButton';
import { Segmented } from '@/components/ui/Segmented';
import { tip } from '@/components/ui/Tooltip';
import { exportFileName, fmtDate, MONTH_NAMES, todayDay, weekdayOf } from '@/lib/format';
import { plural } from '@/lib/text';
import { useNow } from '@/lib/useNow';
import { HOLIDAY_TYPES, term } from '@/lib/vocabulary';
import { HolidayEditor, HolidayImport } from './HolidayDialogs';
import '@/components/data/DataTable.module.css';
import '@/components/layout/Card.module.css';
import { ScrollArea } from '@/components/layout/ScrollArea';
import './Holidays.module.css';

const DEFAULT_SORT: SortState = { key: 'date', dir: 'asc' };
const pad = (n: number) => String(n).padStart(2, '0');

function TypeChip({ type }: { type: string }) {
  const t = term(HOLIDAY_TYPES, type);
  return <Chip tone={t?.tone ?? 'grey'}>{t?.label ?? type}</Chip>;
}

const placeOf = (h: HolidayView) => h.location?.name ?? 'All locations';

export function HolidaysPage() {
  const [params, setParams] = useSearchParams();
  const year = Number(params.get('year')) || Number(todayDay().slice(0, 4));
  const view = params.get('view') === 'list' ? 'list' : 'year';
  const list = useHolidayYear(year).data ?? [];
  const [editing, setEditing] = useState<{ holiday: HolidayView | null; date: string } | null>(null);
  const [importing, setImporting] = useState(false);
  const setParam = (key: string, value: string | null): void => {
    setParams(
      prev => {
        const next = new URLSearchParams(prev);
        if (value === null) next.delete(key);
        else next.set(key, value);
        return next;
      },
      { replace: true },
    );
  };
  const add = (date = ''): void => {
    setEditing({ holiday: null, date });
  };
  const open = (h: HolidayView): void => {
    setEditing({ holiday: h, date: h.date });
  };
  const importButton = (
    <Button
      icon="upload"
      onClick={() => {
        setImporting(true);
      }}
    >
      Import CSV
    </Button>
  );
  const publicCount = list.filter(h => h.type === 'PUBLIC').length;
  return (
    <>
      <PageHead
        title="Holidays"
        sub="Public holidays turn a working day into “Holiday” in attendance. Optional holidays are listed for reference; leave for them is handled in KRZ HRMS."
        actions={
          <>
            {importButton}
            <Button
              variant="primary"
              icon="plus"
              onClick={() => {
                add();
              }}
            >
              Add holiday
            </Button>
          </>
        }
      />
      <HrmsBanner />
      {list.some(h => h.sample) && (
        <Callout tone="warn" icon="alert" className="mb-12 small">
          <b>Sample list.</b> These dates were loaded as an example. Check them against the company holiday calendar
          before go-live.
        </Callout>
      )}
      <div className="row between wrap gap-8 mb-12">
        <div className="row gap-4">
          <IconButton
            icon="chevLeft"
            bordered
            label="Previous year"
            onClick={() => {
              setParam('year', String(year - 1));
            }}
          />
          <b style={{ minWidth: 52, textAlign: 'center' }} aria-live="polite">
            {year}
          </b>
          <IconButton
            icon="chevRight"
            bordered
            label="Next year"
            onClick={() => {
              setParam('year', String(year + 1));
            }}
          />
          <span className="muted small">
            {plural(publicCount, 'public holiday')} · {list.length - publicCount} optional
          </span>
        </div>
        <Segmented
          label="View"
          value={view}
          segments={[
            { value: 'year', label: 'Year', icon: 'calendar' },
            { value: 'list', label: 'List', icon: 'list' },
          ]}
          onChange={v => {
            setParam('view', v === 'list' ? 'list' : null);
          }}
        />
      </div>
      {view === 'year' ? (
        <YearView year={year} list={list} onAdd={add} onOpen={open} importButton={importButton} />
      ) : (
        <ListView
          year={year}
          onAdd={() => {
            add();
          }}
          onOpen={open}
        />
      )}
      {editing && (
        <HolidayEditor
          holiday={editing.holiday}
          date={editing.date}
          onClose={() => {
            setEditing(null);
          }}
        />
      )}
      {importing && (
        <HolidayImport
          year={year}
          onClose={() => {
            setImporting(false);
          }}
        />
      )}
    </>
  );
}

function YearView({
  year,
  list,
  onAdd,
  onOpen,
  importButton,
}: {
  year: number;
  list: readonly HolidayView[];
  onAdd: (date: string) => void;
  onOpen: (h: HolidayView) => void;
  importButton: ReactNode;
}) {
  const today = todayDay();
  const offs = useShifts().data?.find(s => s.defaultShift)?.today.weeklyOffs ?? [];
  const byDate = new Map<string, HolidayView[]>();
  for (const h of list) byDate.set(h.date, [...(byDate.get(h.date) ?? []), h]);
  const month = (mo: number) => {
    const first = `${String(year)}-${pad(mo + 1)}-01`;
    const lead = (weekdayOf(first) + 6) % 7;
    const days = new Date(Date.UTC(year, mo + 1, 0)).getUTCDate();
    return (
      <div key={mo} className="yr-month">
        <div className="yr-title">{MONTH_NAMES[mo]}</div>
        <div className="yr-grid">
          {['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((x, i) => (
            <span key={`${x}${String(i)}`} className="yr-dow" aria-hidden="true">
              {x}
            </span>
          ))}
          {Array.from({ length: lead }, (_, i) => (
            <span key={`lead${String(i)}`} />
          ))}
          {Array.from({ length: days }, (_, i) => {
            const d = i + 1;
            const ds = `${String(year)}-${pad(mo + 1)}-${pad(d)}`;
            const hs = byDate.get(ds);
            const first = hs?.[0];
            if (hs && first) {
              return (
                <button
                  key={ds}
                  type="button"
                  className={`yr-day hol ${first.type === 'OPTIONAL' ? 'opt' : ''}`}
                  aria-label={`${fmtDate(ds)}: ${hs.map(h => h.name).join(', ')}`}
                  {...tip(hs.map(h => `${h.name}${h.location ? ` (${h.location.name})` : ''}`).join(' · '))}
                  onClick={() => {
                    onOpen(first);
                  }}
                >
                  {d}
                </button>
              );
            }
            return (
              <button
                key={ds}
                type="button"
                className={`yr-day ${offs.includes(weekdayOf(ds)) ? 'off' : ''} ${ds === today ? 'today' : ''}`}
                aria-label={`Add a holiday on ${fmtDate(ds)}`}
                onClick={() => {
                  onAdd(ds);
                }}
              >
                {d}
              </button>
            );
          })}
        </div>
      </div>
    );
  };
  return (
    <>
      <div className="card">
        <div className="card-body">
          <div className="yr">{Array.from({ length: 12 }, (_, i) => month(i))}</div>
          <Legend
            className="mt-12"
            entries={[
              { colour: 'var(--blue-bg)', border: '1px solid var(--blue)', label: 'Public holiday' },
              { colour: 'var(--surface-3)', border: '1px dashed var(--text-3)', label: 'Optional' },
              { colour: 'var(--surface-2)', label: 'Weekly off in the default shift' },
              { label: 'Click a day to add or edit' },
            ]}
          />
        </div>
      </div>
      <div className="card mt-12">
        <div className="card-head">
          <h2>{year} at a glance</h2>
        </div>
        <div className="card-body flush">
          {list.length > 0 ? (
            <ScrollArea className="dt-wrap" label="Holidays">
              <table className="dt">
                <tbody>
                  {list.map(h => (
                    <tr
                      key={h.code}
                      className="clickable"
                      tabIndex={0}
                      onClick={() => {
                        onOpen(h);
                      }}
                      onKeyDown={event => {
                        if (event.key === 'Enter' && event.target === event.currentTarget) onOpen(h);
                      }}
                    >
                      <td className="nowrap" style={{ width: 150 }}>
                        <b>{fmtDate(h.date)}</b>
                      </td>
                      <td className="muted small" style={{ width: 110 }}>
                        {h.weekday}
                      </td>
                      <td>{h.name}</td>
                      <td>
                        <TypeChip type={h.type} />
                      </td>
                      <td className="small">{placeOf(h)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollArea>
          ) : (
            <EmptyState icon="sun" title={`No holidays in ${String(year)}`} action={importButton} />
          )}
        </div>
      </div>
    </>
  );
}

function ListView({ year, onAdd, onOpen }: { year: number; onAdd: () => void; onOpen: (h: HolidayView) => void }) {
  const facets = useHolidayFacets(year).data;
  const now = useNow(60_000);
  const filters = useMemo<readonly TableFilter[]>(
    () => [
      { key: 'type', label: 'Type', options: facets?.type ?? [] },
      { key: 'loc', label: 'Location', options: facets?.location ?? [] },
    ],
    [facets],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = { year, type: state.values.type ?? [], location: state.values.loc ?? [], q: state.q };
  const result = useHolidays({
    ...filterQuery,
    sort: state.sort ? `${state.sort.key},${state.sort.dir}` : null,
    page: state.page - 1,
    size: state.size,
  });
  const columns = useMemo<readonly Column<HolidayView>[]>(
    () => [
      {
        key: 'date',
        label: 'Date',
        sort: 'date',
        lock: true,
        render: h => (
          <>
            <b className="nowrap">{fmtDate(h.date)}</b>
            <div className="muted xs">{h.weekday}</div>
          </>
        ),
      },
      { key: 'name', label: 'Holiday', sort: 'name', render: h => h.name },
      { key: 'type', label: 'Type', sort: 'type', render: h => <TypeChip type={h.type} /> },
      { key: 'loc', label: 'Applies to', render: placeOf },
      { key: 'src', label: 'Source', render: h => (h.sample ? <Tag>Sample</Tag> : h.source) },
    ],
    [],
  );
  return (
    <DataTable
      id="holidays"
      control={control}
      filters={filters}
      columns={columns}
      rowKey={h => h.code}
      result={result}
      noun="holidays"
      noun1="holiday"
      searchPlaceholder="Search holidays"
      empty={{
        icon: 'sun',
        title: `No holidays in ${String(year)}`,
        action: (
          <Button variant="primary" onClick={onAdd}>
            Add holiday
          </Button>
        ),
      }}
      exportCsv={{
        href: apiUrl('/holidays/export', filterQuery),
        fileName: exportFileName('holidays', now),
        permission: 'events.export',
      }}
      onRow={onOpen}
    />
  );
}
