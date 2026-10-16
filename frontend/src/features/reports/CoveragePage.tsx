/**
 * Enrolment coverage (the prototype's #/reports/coverage): how many employees the entrance camera can record, the
 * coverage groups by department, and the employees it does not (fully) cover with why and the way to enrol them.
 */
import { useMemo } from 'react';
import { useNavigate } from 'react-router';
import { apiUrl } from '@/api/client';
import { useCoverage, useCoverageFacets, useUncovered, type Coverage, type Uncovered } from '@/api/reports';
import { StackedBarChart, type Segment } from '@/components/charts/BarCharts';
import { Legend } from '@/components/charts/Legend';
import { DataTable, type Column } from '@/components/data/DataTable';
import { KpiCard } from '@/components/data/KpiCard';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import { FaceStatusChip } from '@/components/domain/Chips';
import { EmployeeIdText, PersonCell } from '@/components/domain/PersonCell';
import { ErrorState } from '@/components/feedback/EmptyState';
import { Card } from '@/components/layout/Card';
import { PageHead } from '@/components/layout/PageHead';
import { ButtonLink } from '@/components/ui/Button';
import { exportFileName } from '@/lib/format';
import { useCan } from '@/lib/permissions';
import { useNow } from '@/lib/useNow';

/** The prototype's COVERAGE_SEGMENTS: the coverage groups, in its order and colours. */
const SEGMENTS: readonly Segment[] = [
  { key: 'TRACKED', label: 'Tracked', color: 'var(--green-line)' },
  { key: 'REENROL', label: 'Needs re-enrolment', color: 'var(--amber-line)' },
  { key: 'PENDING', label: 'Consent pending', color: 'var(--purple-line)' },
  { key: 'NO_FACE', label: 'No face profile', color: 'var(--grey-line)' },
  { key: 'NO_CAMERA', label: 'No camera at location', color: 'var(--blue)' },
  { key: 'EXEMPT', label: 'Exempt', color: 'var(--border-strong)' },
];

/** The groups that enrolment can fix. */
const ENROLABLE = new Set(['REENROL', 'PENDING', 'NO_FACE']);

const DEFAULT_SORT: SortState = { key: 'why', dir: 'asc' };

export function CoveragePage() {
  const coverage = useCoverage();
  return (
    <>
      <PageHead
        title="Enrolment coverage"
        sub="How many employees the entrance camera can record. Everyone else is recorded by HR in KRZ HRMS."
      />
      {coverage.isError ? (
        <ErrorState what="the coverage" onRetry={() => void coverage.refetch()} />
      ) : (
        coverage.data && (
          <>
            <Kpis coverage={coverage.data} />
            <Card title="By department" className="mb-16" bodyClassName="chart" actions={<Legend entries={legend()} />}>
              <StackedBarChart
                items={coverage.data.departments.map(d => ({ label: d.name, values: d.groups }))}
                segments={SEGMENTS}
              />
            </Card>
          </>
        )
      )}
      <UncoveredTable />
    </>
  );
}

function legend() {
  return SEGMENTS.map(s => ({ label: s.label, colour: s.color }));
}

function Kpis({ coverage: c }: { coverage: Coverage }) {
  const count = (group: string) => c.groups[group] ?? 0;
  const at = c.cameraLocations.length ? ` at ${c.cameraLocations.join(', ')}` : '';
  return (
    <div className="kpis">
      <KpiCard
        label="Covered by camera"
        value={`${String(c.eligible ? Math.round((100 * c.covered) / c.eligible) : 0)}%`}
        foot={`${String(c.covered)} of ${String(c.eligible)}${at}`}
        icon="eye"
        tone="green"
        to="/employees"
      />
      <KpiCard
        label="Needs re-enrolment"
        value={count('REENROL')}
        foot="Still tracked, lower accuracy"
        icon="refresh"
        tone="amber"
        to="/employees?filter=needs-reenrol"
      />
      <KpiCard
        label="Consent pending"
        value={count('PENDING')}
        foot="Waiting for the form"
        icon="hourglass"
        tone="purple"
        to="/employees?filter=not-enrolled"
      />
      <KpiCard
        label="No face profile"
        value={count('NO_FACE')}
        foot="Not enrolled or erased"
        icon="scanface"
        tone="grey"
        to="/employees?filter=not-enrolled"
      />
      <KpiCard
        label="No camera at location"
        value={count('NO_CAMERA')}
        foot={c.noCameraLocations.join(', ') || '—'}
        icon="building"
        tone="blue"
        to="/employees"
      />
      <KpiCard label="Exempt" value={count('EXEMPT')} foot="By policy" icon="eyeOff" tone="grey" to="/employees" />
    </div>
  );
}

function UncoveredTable() {
  const now = useNow(60_000);
  const navigate = useNavigate();
  const canView = useCan('people.view');
  const canEnrol = useCan('people.enrol');
  const facets = useCoverageFacets().data;
  const filters = useMemo<readonly TableFilter[]>(
    () => [
      { key: 'group', label: 'Reason', options: facets?.group ?? [] },
      { key: 'department', label: 'Department', options: facets?.department ?? [] },
    ],
    [facets],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = { group: state.values.group ?? [], department: state.values.department ?? [], q: state.q };
  const sort = state.sort ? `${state.sort.key},${state.sort.dir}` : null;
  const result = useUncovered({ ...filterQuery, sort, page: state.page - 1, size: state.size });
  const columns = useMemo<readonly Column<Uncovered>[]>(
    () => [
      {
        key: 'name',
        label: 'Employee',
        sort: 'name',
        lock: true,
        render: e => <PersonCell id={e.id} name={e.name} sub={<EmployeeIdText employeeId={e.employeeId} />} />,
      },
      { key: 'dept', label: 'Department', sort: 'department', render: e => e.department ?? '—' },
      { key: 'loc', label: 'Location', sort: 'location', render: e => e.location ?? '—' },
      { key: 'face', label: 'Face', sort: 'face', render: e => <FaceStatusChip status={e.faceStatus} /> },
      {
        key: 'why',
        label: 'Why not covered',
        sort: 'why',
        render: e => (
          <>
            {SEGMENTS.find(s => s.key === e.group)?.label ?? e.group}
            {e.faceReason && <div className="muted xs">{e.faceReason}</div>}
          </>
        ),
      },
      {
        key: 'act',
        label: '',
        render: e =>
          canEnrol && ENROLABLE.has(e.group) ? (
            <ButtonLink size="sm" to={`/enrol?employee=${e.id}`}>
              {e.templates ? 'Re-enrol' : 'Enrol'}
            </ButtonLink>
          ) : null,
      },
    ],
    [canEnrol],
  );
  return (
    <DataTable
      id="coverage"
      control={control}
      filters={filters}
      columns={columns}
      rowKey={e => e.id}
      result={result}
      noun="employees"
      noun1="employee"
      empty={{ icon: 'checkCircle', title: 'Everyone is covered' }}
      exportCsv={{
        href: apiUrl('/reports/coverage/export', { ...filterQuery, sort }),
        fileName: exportFileName('enrolment-coverage', now),
        permission: 'events.export',
      }}
      {...(canView
        ? {
            onRow: (e: Uncovered) => {
              void navigate(`/employees/${e.id}`);
            },
          }
        : {})}
    />
  );
}
