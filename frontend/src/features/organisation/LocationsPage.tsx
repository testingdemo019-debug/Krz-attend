/**
 * Locations (new in the specification; Departments' page pattern): sites with their entry points, cameras and
 * employees. A row (or ?id=) opens the location drawer; "New location" and Edit open the two-step editor. Deactivating
 * a location in use is blocked with the reason, as the server blocks it.
 */
import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { apiUrl } from '@/api/client';
import {
  useLocationDetail,
  useLocationFacets,
  useLocations,
  useLocationStatus,
  type LocationView,
} from '@/api/organisation';
import { DataTable, type Column } from '@/components/data/DataTable';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import { HrmsBanner, SourceTag } from '@/components/domain/HrmsBanner';
import { Drawer } from '@/components/feedback/Drawer';
import { toast } from '@/components/feedback/toast';
import { PageHead } from '@/components/layout/PageHead';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { DASH, exportFileName, fmtDate } from '@/lib/format';
import { plural } from '@/lib/text';
import { useNow } from '@/lib/useNow';
import { RECORD_STATUS, term } from '@/lib/vocabulary';
import { LocationEditor } from './LocationEditor';
import '@/components/data/DataTable.module.css';

const DEFAULT_SORT: SortState = { key: 'name', dir: 'asc' };

function StatusChip({ status }: { status: string }) {
  const t = term(RECORD_STATUS, status);
  return <Chip tone={t?.tone ?? 'grey'}>{t?.label ?? status}</Chip>;
}

const COLUMNS: readonly Column<LocationView>[] = [
  { key: 'code', label: 'Code', sort: 'code', render: l => <span className="mono">{l.code}</span> },
  { key: 'name', label: 'Name', sort: 'name', lock: true, render: l => <b>{l.name}</b> },
  { key: 'address', label: 'Address', render: l => <span className="small">{l.address ?? DASH}</span> },
  {
    key: 'points',
    label: 'Entry points',
    num: true,
    render: l => l.entryPoints.filter(p => p.status === 'ACTIVE').length,
  },
  { key: 'cameras', label: 'Cameras', sort: 'cameras', num: true, render: l => l.cameras },
  { key: 'employees', label: 'Employees', sort: 'employees', num: true, render: l => l.employees },
  { key: 'status', label: 'Status', sort: 'status', render: l => <StatusChip status={l.status} /> },
];

/** Why the server would refuse to deactivate it (its LOCATION_IN_USE message), or null. */
export function locationInUse(l: LocationView): string | null {
  const cameras = l.entryPoints.reduce((n, p) => n + p.enabledCameras, 0);
  const users = [
    ...(cameras > 0 ? [plural(cameras, 'enabled camera')] : []),
    ...(l.employees > 0 ? [plural(l.employees, 'employee')] : []),
  ];
  return users.length ? `${l.name} is used by ${users.join(' and ')} — move them to another location first.` : null;
}

export function LocationsPage() {
  const [params, setParams] = useSearchParams();
  const now = useNow(60_000);
  const facets = useLocationFacets().data;
  const [editing, setEditing] = useState<{ location: LocationView | null } | null>(null);
  const filters = useMemo<readonly TableFilter[]>(
    () => [{ key: 'status', label: 'Status', defaultValues: ['ACTIVE'], options: facets?.status ?? [] }],
    [facets],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = { status: state.values.status ?? [], q: state.q };
  const result = useLocations({
    ...filterQuery,
    sort: state.sort ? `${state.sort.key},${state.sort.dir}` : null,
    page: state.page - 1,
    size: state.size,
  });
  const openCode = params.get('id');
  const open = useLocationDetail(openCode).data ?? null;
  const setOpen = (code: string | null): void => {
    setParams(
      prev => {
        const next = new URLSearchParams(prev);
        if (code) next.set('id', code);
        else next.delete('id');
        return next;
      },
      { replace: true },
    );
  };
  const newButton = (
    <Button
      variant="primary"
      icon="plus"
      onClick={() => {
        setEditing({ location: null });
      }}
    >
      New location
    </Button>
  );
  return (
    <>
      <PageHead
        title="Locations"
        sub="Sites and their entry points. Cameras are mounted at entry points; employees work at a location."
        actions={newButton}
      />
      <HrmsBanner />
      <DataTable
        id="locations"
        control={control}
        filters={filters}
        columns={COLUMNS}
        rowKey={l => l.code}
        result={result}
        noun="locations"
        noun1="location"
        searchPlaceholder="Search locations"
        empty={{ icon: 'pin', title: 'No locations yet', action: newButton }}
        exportCsv={{
          href: apiUrl('/locations/export', filterQuery),
          fileName: exportFileName('locations', now),
          permission: 'events.export',
        }}
        onRow={l => {
          setOpen(l.code);
        }}
      />
      {open && (
        <LocationDrawer
          location={open}
          onClose={() => {
            setOpen(null);
          }}
          onEdit={() => {
            setOpen(null);
            setEditing({ location: open });
          }}
        />
      )}
      {editing && (
        <LocationEditor
          location={editing.location}
          onClose={() => {
            setEditing(null);
          }}
        />
      )}
    </>
  );
}

function LocationDrawer({
  location: l,
  onClose,
  onEdit,
}: {
  location: LocationView;
  onClose: () => void;
  onEdit: () => void;
}) {
  const status = useLocationStatus();
  const active = l.status === 'ACTIVE';
  const blocked = active ? locationInUse(l) : null;
  const change = (archive: boolean): void => {
    status.mutate(
      { code: l.code, archive },
      {
        onSuccess: () => {
          onClose();
          toast(`${l.name} ${archive ? 'deactivated' : 'reactivated'}`, { type: 'success', logged: true });
        },
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };
  return (
    <Drawer
      open
      wide
      onClose={onClose}
      title={l.name}
      sub={`${l.code} · ${term(RECORD_STATUS, l.status)?.label ?? l.status}`}
      footer={
        <>
          {active ? (
            <Button
              icon="ban"
              disabled={blocked !== null}
              tooltip={blocked ?? undefined}
              pending={status.isPending}
              onClick={() => {
                change(true);
              }}
            >
              Deactivate
            </Button>
          ) : (
            <Button
              icon="refresh"
              pending={status.isPending}
              onClick={() => {
                change(false);
              }}
            >
              Reactivate
            </Button>
          )}
          <span className="grow" />
          <Button variant="primary" icon="edit" disabled={!active} onClick={onEdit}>
            Edit
          </Button>
        </>
      }
    >
      <KeyValueList
        pairs={[
          ['Code', <span className="mono">{l.code}</span>],
          ['Address', l.address ?? DASH],
          ['Employees', String(l.employees)],
          ['Camera', l.hasCamera ? 'Yes — attendance can be tracked here' : 'No — employees here are not tracked'],
          ['Created', fmtDate(l.createdAt)],
          ['Source', <SourceTag />],
        ]}
      />
      <h3 className="mt-16 mb-8">Entry points</h3>
      {l.entryPoints.length > 0 ? (
        <ScrollArea className="dt-wrap" label="Entry points">
          <table className="dt compact">
            <thead>
              <tr>
                <th>Entry point</th>
                <th>Code</th>
                <th className="num">Cameras</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {l.entryPoints.map(p => (
                <tr key={p.code}>
                  <td>{p.name}</td>
                  <td className="mono">{p.code}</td>
                  <td className="num">
                    {p.cameras}
                    {p.cameras !== p.enabledCameras ? ` (${String(p.enabledCameras)} enabled)` : ''}
                  </td>
                  <td>
                    <StatusChip status={p.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollArea>
      ) : (
        <p className="muted small">No entry points yet. Add one to mount a camera.</p>
      )}
    </Drawer>
  );
}
