/**
 * Events (the prototype's #/events): every crossing with the punch effect the rules gave it, newest first, served page
 * by page. Default range yesterday and today; ?day= and ?employee= set the filters, ?id= opens a crossing.
 */
import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { apiUrl } from '@/api/client';
import { useCrossingFacets, useCrossings } from '@/api/crossings';
import type { EventRow } from '@/api/live';
import { useMeta } from '@/api/me';
import { useDetailDrawers } from '@/app/DetailDrawers';
import { DataTable, type Column } from '@/components/data/DataTable';
import {
  rangeOf,
  useTableState,
  type FilterOption,
  type RangeFilter,
  type SortState,
  type TableFilter,
} from '@/components/data/tableState';
import {
  ConfidenceBadge,
  DecisionText,
  DirectionChip,
  EffectChip,
  LivenessBadge,
  MethodLabel,
} from '@/components/domain/Chips';
import { CropImage } from '@/components/domain/CropImage';
import { EmployeeIdText, PersonCell } from '@/components/domain/PersonCell';
import { Modal } from '@/components/feedback/Modal';
import { PageHead } from '@/components/layout/PageHead';
import { Button } from '@/components/ui/Button';
import { Tag } from '@/components/ui/Chip';
import { addDays, exportFileName, fmtDate, fmtTime, todayDay } from '@/lib/format';
import { useNow } from '@/lib/useNow';
import { DECISIONS, EFFECTS, METHODS } from '@/lib/vocabulary';

const DEFAULT_SORT: SortState = { key: 'time', dir: 'desc' };
/** "All days" once the default range is cleared: the crossings' retention (R16, 365 days). */
const RETENTION_DAYS = 365;

function withCounts(options: readonly FilterOption[], counts: readonly { value: string; count: number }[] | undefined) {
  return options.map(o => ({ ...o, count: counts?.find(c => c.value === o.value)?.count ?? 0 }));
}

function columns(cameras: number): readonly Column<EventRow>[] {
  return [
    {
      key: 'crop',
      label: 'Crop',
      render: r => (
        <CropImage
          crossing={r.code}
          kind={r.faceCrop ? 'face' : 'body'}
          available={!r.cropsPurged && (r.faceCrop || r.bodyCrop)}
        />
      ),
    },
    {
      key: 'time',
      label: 'Time',
      sort: 'time',
      render: r => (
        <>
          <span className="nowrap num">{fmtTime(r.occurredAt, true)}</span>
          <div className="muted xs nowrap">{fmtDate(r.occurredAt)}</div>
        </>
      ),
    },
    { key: 'camera', label: 'Camera', hidden: cameras < 2, render: r => r.camera.name },
    {
      key: 'dir',
      label: 'Direction',
      sort: 'direction',
      render: r => (
        <>
          <DirectionChip direction={r.direction} />
          {r.flippedFrom && (
            <>
              {' '}
              <Tag title={`Direction corrected from ${r.flippedFrom}`}>flipped</Tag>
            </>
          )}
        </>
      ),
    },
    {
      key: 'person',
      label: 'Person',
      sort: 'person',
      render: r =>
        r.person ? (
          <PersonCell id={r.person.id} name={r.person.name} sub={<EmployeeIdText employeeId={r.person.employeeId} />} />
        ) : (
          <span className="muted">{r.unknownLabel ?? 'Unknown face'}</span>
        ),
    },
    {
      key: 'method',
      label: 'Method',
      sort: 'method',
      render: r => (
        <>
          <MethodLabel method={r.method} />
          {(r.method === 'FACE' || r.method === 'REID') && (
            <div className="mt-4">
              <ConfidenceBadge band={r.confidence} score={r.score} />
            </div>
          )}
        </>
      ),
    },
    {
      key: 'decision',
      label: 'Decision',
      sort: 'decision',
      render: r => (
        <>
          <DecisionText decision={r.decision} />
          {r.liveness === 'FAILED' && (
            <div className="mt-4">
              <LivenessBadge result="FAILED" />
            </div>
          )}
        </>
      ),
    },
    { key: 'effect', label: 'Punch effect', sort: 'effect', render: r => <EffectChip effect={r.effect} /> },
    {
      key: 'reason',
      label: 'Rule reason',
      width: '28%',
      render: r => <span className="small muted-2">{r.effectReason}</span>,
    },
    { key: 'id', label: 'Event ID', sort: 'code', hidden: true, render: r => <span className="mono">{r.code}</span> },
  ];
}

/** The prototype's showPunchRulesHelp, with this organisation's rule values. */
function PunchRulesHelp({ onClose }: { onClose: () => void }) {
  const rules = useMeta(true).data?.punchRules;
  return (
    <Modal open size="lg" title="How punch rules work" onClose={onClose}>
      <ol className="small" style={{ paddingLeft: 18, display: 'flex', flexDirection: 'column', gap: 8, margin: 0 }}>
        <li>
          <b>One camera, two directions.</b> Walking towards the camera (face visible) is <b>IN</b>. Walking away (back
          to the camera) is <b>OUT</b>, identified by Re-ID against people who came in that day.
        </li>
        <li>
          <b>Shift day.</b> Each crossing belongs to the employee’s shift day: from the check-in window (a few hours
          before shift start) until day close (a few hours after shift end).
        </li>
        <li>
          <b>Duplicates and turn-backs are ignored.</b> Same direction within {rules?.duplicateMinutes} min is a
          duplicate. IN then OUT (or OUT then IN) within {rules?.turnedBackSeconds} s means they turned back. A U-turn
          at the tripwire is ignored.
        </li>
        <li>
          <b>First IN is the check-in.</b> Later INs are movements.
        </li>
        <li>
          <b>OUT before shift end is “Stepped out”.</b> It is a movement; the check-out stays open in case they come
          back.
        </li>
        <li>
          <b>OUT after shift end is the check-out.</b> It stays tentative — a later OUT replaces it — until day close.
        </li>
        <li>
          <b>Day close finalises the day.</b> Left early and didn’t come back → <b>Early exit</b> with that OUT as
          check-out. Still inside → <b>Missing check-out</b>, which KRZ HRMS regularises.
        </li>
        <li>
          <b>Only final punches go to KRZ HRMS</b> (
          {rules?.pushMode === 'EVERY' ? 'every counted crossing' : 'check-in, and check-out after day close'}).
          Corrections void what was already sent and re-send.
        </li>
      </ol>
      <p className="small muted mt-12">
        Change these in Settings → Attendance rules. The rule tester there uses the same engine as this page.
      </p>
    </Modal>
  );
}

export function EventsPage() {
  const [params, setParams] = useSearchParams();
  const { openEvent } = useDetailDrawers();
  const [help, setHelp] = useState(false);
  const now = useNow(60_000);
  const today = todayDay(now);
  const yesterday = addDays(today, -1);
  const dayParam = params.get('day');
  const employeeParam = params.get('employee');
  const idParam = params.get('id');

  // The prototype's ?day= and ?employee= become the table's filters once.
  useEffect(() => {
    if (!dayParam && !employeeParam) return;
    setParams(
      prev => {
        const out = new URLSearchParams(prev);
        out.delete('day');
        out.delete('employee');
        out.set('dateFrom', dayParam ?? addDays(todayDay(), -13));
        out.set('dateTo', dayParam ?? todayDay());
        if (employeeParam) out.set('employee', employeeParam);
        return out;
      },
      { replace: true },
    );
  }, [dayParam, employeeParam, setParams]);

  // ?id= opens that crossing; closing it removes the parameter.
  useEffect(() => {
    if (!idParam) return;
    openEvent(idParam, () => {
      setParams(
        prev => {
          const out = new URLSearchParams(prev);
          out.delete('id');
          return out;
        },
        { replace: true },
      );
    });
  }, [idParam, openEvent, setParams]);

  const dateFilter = useMemo<RangeFilter>(
    () => ({ key: 'date', label: 'Date', range: true, defaultRange: { from: yesterday, to: today } }),
    [yesterday, today],
  );
  const range = rangeOf(params, dateFilter);
  const from = range.from || addDays(today, -RETENTION_DAYS);
  const to = range.to || today;
  const facets = useCrossingFacets(from, to).data;

  const filters = useMemo<readonly TableFilter[]>(
    () => [
      dateFilter,
      {
        key: 'dir',
        label: 'Direction',
        options: withCounts(
          ['IN', 'OUT'].map(d => ({ value: d, label: d })),
          facets?.direction,
        ),
      },
      {
        key: 'method',
        label: 'Method',
        options: withCounts(
          [
            ...Object.entries(METHODS).map(([value, m]) => ({ value, label: m.label })),
            { value: 'NONE', label: 'Not identified' },
          ],
          facets?.method,
        ),
      },
      {
        key: 'decision',
        label: 'Decision',
        options: withCounts(
          Object.entries(DECISIONS).map(([value, label]) => ({ value, label })),
          facets?.decision,
        ),
      },
      {
        key: 'effect',
        label: 'Punch effect',
        options: withCounts(
          Object.entries(EFFECTS).map(([value, e]) => ({ value, label: e.label })),
          facets?.effect,
        ),
      },
      {
        key: 'employee',
        label: 'Employee',
        options: (facets?.employee ?? []).map(e => ({ value: e.value, label: e.label, count: e.count })),
      },
      {
        key: 'camera',
        label: 'Camera',
        options: (facets?.camera ?? []).map(c => ({ value: c.value, label: c.label, count: c.count })),
      },
    ],
    [dateFilter, facets],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = {
    from,
    to,
    direction: state.values.dir ?? [],
    method: state.values.method ?? [],
    decision: state.values.decision ?? [],
    effect: state.values.effect ?? [],
    employeeId: state.values.employee ?? [],
    camera: state.values.camera ?? [],
    q: state.q,
    sort: state.sort ? `${state.sort.key},${state.sort.dir}` : null,
  };
  const result = useCrossings({ ...filterQuery, page: state.page - 1, size: state.size });
  const tableColumns = useMemo(() => columns(facets?.camera.length ?? 1), [facets?.camera.length]);

  return (
    <>
      <PageHead
        title="Events"
        sub="Every crossing at the entrance camera — IN when someone faces the camera, OUT when they leave with their back to it. The punch effect shows what the rules did with it."
        actions={
          <Button
            icon="help"
            iconSize="default"
            onClick={() => {
              setHelp(true);
            }}
          >
            How punch rules work
          </Button>
        }
      />
      <DataTable
        id="events"
        control={control}
        filters={filters}
        columns={tableColumns}
        rowKey={r => r.code}
        result={result}
        noun="crossings"
        noun1="crossing"
        searchPlaceholder="Search by name, Employee ID, event ID or label"
        empty={{
          icon: 'list',
          title: 'No crossings in this range',
          text: 'Crossings appear here as people walk past the entrance camera. Try a wider date range.',
        }}
        exportCsv={{
          href: apiUrl('/crossings/export', filterQuery),
          fileName: exportFileName('events', now),
          permission: 'events.export',
        }}
        onRow={r => {
          openEvent(r.code);
        }}
      />
      {help && (
        <PunchRulesHelp
          onClose={() => {
            setHelp(false);
          }}
        />
      )}
    </>
  );
}
