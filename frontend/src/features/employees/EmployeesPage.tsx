/**
 * Employees (the prototype's #/employees): identity from Microsoft Entra ID, employment kept here until KRZ HRMS.
 * Every filter with counts, bulk actions, "Sync now"; ?filter= (missing-id, needs-reenrol, not-enrolled,
 * missing-employment) and ?dept= as in the prototype; a row opens the profile.
 */
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { apiUrl } from '@/api/client';
import { useDirectorySummary, useEmployeeFacets, useEmployees, type EmployeeListItem } from '@/api/employees';
import { useDetailDrawers } from '@/app/DetailDrawers';
import { DataTable, type BulkAction, type Column } from '@/components/data/DataTable';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import {
  AccountChip,
  ConsentChip,
  EmploymentMissingChip,
  FaceStatusChip,
  TrackingChip,
} from '@/components/domain/Chips';
import { SourceTag } from '@/components/domain/HrmsBanner';
import { EmployeeIdText, PersonCell } from '@/components/domain/PersonCell';
import { Icon } from '@/components/icons/Icon';
import { PageHead } from '@/components/layout/PageHead';
import { ButtonLink } from '@/components/ui/Button';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { DASH, exportFileName, fmtDate, fmtDateTime } from '@/lib/format';
import { useCan, useNeedsPermissionText } from '@/lib/permissions';
import { plural } from '@/lib/text';
import { useNow } from '@/lib/useNow';
import { EMPLOYMENT_TYPES, FACE_STATUS, TRACKING_REASONS, term } from '@/lib/vocabulary';
import { BulkDepartmentDialog, BulkTrackingDialog } from './BulkDialogs';
import { SyncNowButton } from './SyncNowButton';

const DEFAULT_SORT: SortState = { key: 'name', dir: 'asc' };

/** The prototype's ?filter= links, as the table's filters (missing-employment stays a server filter). */
const QUICK_FILTERS: Record<string, Record<string, readonly string[]>> = {
  'missing-id': { eid: ['missing'] },
  'needs-reenrol': { face: ['NEEDS_REENROL'] },
  'not-enrolled': { face: ['NOT_ENROLLED', 'CONSENT_PENDING'] },
};

function columns(sensitive: boolean): readonly Column<EmployeeListItem>[] {
  return [
    {
      key: 'name',
      label: 'Employee',
      sort: 'name',
      lock: true,
      render: e => <PersonCell id={e.id} name={e.name} sub={sensitive ? e.email : e.designation} />,
    },
    { key: 'eid', label: 'Employee ID', sort: 'employeeId', render: e => <EmployeeIdText employeeId={e.employeeId} /> },
    { key: 'dept', label: 'Department', sort: 'department', render: e => e.department?.name ?? DASH },
    { key: 'desig', label: 'Designation', sort: 'designation', hidden: true, render: e => e.designation ?? DASH },
    { key: 'loc', label: 'Location', sort: 'location', render: e => e.location?.name ?? DASH },
    {
      key: 'type',
      label: 'Type',
      sort: 'type',
      hidden: true,
      render: e => term(EMPLOYMENT_TYPES, e.employmentType) ?? DASH,
    },
    {
      key: 'shift',
      label: 'Shift today',
      sort: 'shift',
      render: e =>
        e.shiftToday ? (
          <span className="row gap-4 nowrap">
            <span className="sw" style={{ background: e.shiftToday.colour }} />
            {e.shiftToday.name}
          </span>
        ) : (
          DASH
        ),
    },
    { key: 'face', label: 'Face', sort: 'face', render: e => <FaceStatusChip status={e.faceStatus} /> },
    {
      key: 'consent',
      label: 'Consent',
      sort: 'consent',
      hidden: true,
      render: e => <ConsentChip status={e.consentStatus} />,
    },
    {
      key: 'tracking',
      label: 'Tracking',
      sort: 'tracking',
      render: e => (
        <>
          <TrackingChip tracked={e.trackingMode === 'TRACKED' && e.accountEnabled} label={e.trackingLabel} />
          <AccountChip enabled={e.accountEnabled} />
          {e.employmentMissing && <EmploymentMissingChip />}
        </>
      ),
    },
    {
      key: 'join',
      label: 'Joined',
      sort: 'joinDate',
      hidden: true,
      render: e => <span className="nowrap">{fmtDate(e.joinDate)}</span>,
    },
  ];
}

export function EmployeesPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const sensitive = useCan('people.view.sensitive');
  const canEdit = useCan('settings.attendance');
  const canEnrol = useCan('people.enrol');
  const needs = useNeedsPermissionText();
  const now = useNow(60_000);
  const facets = useEmployeeFacets().data;
  const summary = useDirectorySummary().data;
  const { openAllocation } = useDetailDrawers();
  const [dialog, setDialog] = useState<{
    kind: 'department' | 'tracking';
    ids: readonly string[];
    done: () => void;
  } | null>(null);
  const quick = params.get('filter');
  const missingEmployment = quick === 'missing-employment';

  // The prototype's ?filter= links become the table's filters (and show every account, as its links did).
  useEffect(() => {
    const preset = quick ? QUICK_FILTERS[quick] : undefined;
    if (!preset) return;
    setParams(
      prev => {
        const out = new URLSearchParams(prev);
        out.delete('filter');
        out.set('account', '');
        for (const [key, values] of Object.entries(preset)) {
          out.delete(key);
          for (const v of values) out.append(key, v);
        }
        return out;
      },
      { replace: true },
    );
  }, [quick, setParams]);

  const filters = useMemo<readonly TableFilter[]>(() => {
    const count = (list: readonly { value: string; count: number }[] | undefined, value: string) =>
      list?.find(o => o.value === value)?.count ?? 0;
    return [
      { key: 'dept', label: 'Department', options: facets?.department ?? [] },
      { key: 'loc', label: 'Location', options: facets?.location ?? [] },
      {
        key: 'type',
        label: 'Type',
        options: Object.entries(EMPLOYMENT_TYPES).map(([value, label]) => ({
          value,
          label,
          count: count(facets?.type, value),
        })),
      },
      {
        key: 'face',
        label: 'Face status',
        options: Object.entries(FACE_STATUS).map(([value, t]) => ({
          value,
          label: t.label,
          count: count(facets?.face, value),
        })),
      },
      {
        key: 'tracking',
        label: 'Tracking',
        options: [
          { value: 'TRACKED', label: 'Tracked by camera', count: count(facets?.tracking, 'TRACKED') },
          ...Object.entries(TRACKING_REASONS).map(([value, label]) => ({
            value,
            label: `Not tracked — ${label}`,
            count: count(facets?.tracking, value),
          })),
        ],
      },
      { key: 'shift', label: 'Shift today', options: facets?.shift ?? [] },
      {
        key: 'eid',
        label: 'Employee ID',
        options: [
          { value: 'has', label: 'Has an Employee ID', count: count(facets?.employeeId, 'has') },
          { value: 'missing', label: 'Missing', count: count(facets?.employeeId, 'missing') },
        ],
      },
      {
        key: 'account',
        label: 'Account',
        defaultValues: ['enabled'],
        options: [
          { value: 'enabled', label: 'Enabled', count: count(facets?.account, 'enabled') },
          { value: 'disabled', label: 'Disabled', count: count(facets?.account, 'disabled') },
        ],
      },
    ];
  }, [facets]);
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = {
    filter: missingEmployment ? 'missing-employment' : null,
    department: state.values.dept ?? [],
    location: state.values.loc ?? [],
    type: state.values.type ?? [],
    face: state.values.face ?? [],
    tracking: state.values.tracking ?? [],
    shift: state.values.shift ?? [],
    employeeId: state.values.eid ?? [],
    status: state.values.account ?? [],
    q: state.q,
    sort: state.sort ? `${state.sort.key},${state.sort.dir}` : null,
  };
  const result = useEmployees({ ...filterQuery, page: state.page - 1, size: state.size });
  const tableColumns = useMemo(() => columns(sensitive), [sensitive]);
  const bulkActions = useMemo<readonly BulkAction[]>(
    () => [
      {
        label: 'Change department',
        permission: 'settings.attendance',
        run: (ids, done) => {
          setDialog({ kind: 'department', ids, done });
        },
      },
      {
        label: 'Allocate shift',
        permission: 'settings.attendance',
        run: ids => {
          openAllocation({ targetType: 'EMPLOYEE', targetIds: ids });
        },
      },
      {
        label: 'Set tracking',
        permission: 'settings.attendance',
        run: (ids, done) => {
          setDialog({ kind: 'tracking', ids, done });
        },
      },
    ],
    [openAllocation],
  );

  return (
    <>
      <PageHead
        title="Employees"
        sub={
          <>
            Names and work emails come from Microsoft Entra ID. Employee ID, department, shift and tracking are managed
            here for now <SourceTag />
          </>
        }
        actions={
          <>
            <SyncNowButton />
            {canEnrol && (
              <ButtonLink variant="primary" icon="scanface" iconSize="default" to="/enrol">
                Enrol a face
              </ButtonLink>
            )}
          </>
        }
      />
      <div className="row wrap gap-8 mb-12 small muted-2">
        <span className="row gap-4">
          <Icon name="sync" size="sm" />
          {summary?.lastSyncAt
            ? `Last directory sync ${fmtDateTime(summary.lastSyncAt)} · ${
                summary.lastSyncStatus === 'SUCCESS' ? 'no issues' : plural(summary.lastSyncWarnings, 'warning')
              }`
            : 'No directory sync yet'}
        </span>
        {(summary?.missingEmployeeIds ?? 0) > 0 && (
          <Link className="chip chip-amber chip-wrap" to="/employees?filter=missing-id">
            <Icon name="alert" />
            {plural(summary?.missingEmployeeIds ?? 0, 'employee')} without an Employee ID — punches can’t reach HRMS
          </Link>
        )}
        {(summary?.missingEmployment ?? 0) > 0 && (
          <Link className="chip chip-amber chip-wrap" to="/employees?filter=missing-employment">
            <Icon name="alert" />
            {plural(summary?.missingEmployment ?? 0, 'employee')} with employment details missing
          </Link>
        )}
      </div>
      {missingEmployment && (
        <Callout tone="neutral" className="mb-12 small">
          Showing employees whose department, designation, location or join date is not set yet.{' '}
          <Button
            size="sm"
            variant="ghost"
            onClick={() => {
              setParams(
                prev => {
                  const out = new URLSearchParams(prev);
                  out.delete('filter');
                  return out;
                },
                { replace: true },
              );
            }}
          >
            Show everyone
          </Button>
        </Callout>
      )}
      <DataTable
        id="employees"
        control={control}
        filters={filters}
        columns={tableColumns}
        rowKey={e => e.id}
        result={result}
        noun="employees"
        noun1="employee"
        searchPlaceholder="Search by name, Employee ID, email or designation"
        empty={{ icon: 'users', title: 'No employees yet', text: 'Employees appear after the first directory sync.' }}
        exportCsv={{
          href: apiUrl('/employees/export', filterQuery),
          fileName: exportFileName('employees', now),
          permission: 'events.export',
        }}
        onRow={e => {
          void navigate(`/employees/${e.id}`);
        }}
        bulkActions={bulkActions}
      />
      {!canEdit && <p className="muted small mt-8">{needs('settings.attendance')} to edit employment details.</p>}
      {dialog?.kind === 'department' && (
        <BulkDepartmentDialog
          ids={dialog.ids}
          onDone={dialog.done}
          onClose={() => {
            setDialog(null);
          }}
        />
      )}
      {dialog?.kind === 'tracking' && (
        <BulkTrackingDialog
          ids={dialog.ids}
          onDone={dialog.done}
          onClose={() => {
            setDialog(null);
          }}
        />
      )}
    </>
  );
}
