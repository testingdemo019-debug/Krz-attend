/**
 * Settings → KRZ HRMS: KRZ Attend sends punches to KRZ HRMS, which owns regularisation, leave and each employee's own
 * attendance view. The connection comes from the server's configuration (the API key is entered there, never here).
 * Master data stays in KRZ Attend for now. The outbox is the shared table; an entry's payload opens in a drawer (F6.3).
 */
import { useMemo, useState } from 'react';
import { apiUrl } from '@/api/client';
import { useHrms, useOutbox, useOutboxAction, type HrmsOverview, type OutboxEntry } from '@/api/settings';
import { DataTable, type Column } from '@/components/data/DataTable';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import { HrmsStatusChip } from '@/components/domain/Chips';
import { Drawer } from '@/components/feedback/Drawer';
import { ErrorState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Switch } from '@/components/form/Switch';
import { Button, ButtonLink } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { exportFileName, fmtDate, fmtDateTime, fmtTime } from '@/lib/format';
import { useCan } from '@/lib/permissions';
import { plural } from '@/lib/text';
import { useNow } from '@/lib/useNow';
import { HRMS_STATUS, term } from '@/lib/vocabulary';
import '@/components/ui/CodeBlock.module.css';
import { ScrollArea } from '@/components/layout/ScrollArea';

const DEFAULT_SORT: SortState = { key: 'created', dir: 'desc' };
const NEEDS_ATTENTION = ['FAILED', 'BLOCKED_NO_EMPLOYEE_ID'];
/** The prototype's order of the outbox counts. */
const COUNT_ORDER = ['PENDING', 'SENT', 'FAILED', 'VOIDED', 'BLOCKED_NO_EMPLOYEE_ID', 'HELD_NOT_CONNECTED'] as const;

export function HrmsSettings() {
  const overview = useHrms();
  return (
    <div className="card pad">
      {overview.isError ? (
        <ErrorState what="the KRZ HRMS settings" onRetry={() => void overview.refetch()} />
      ) : overview.data ? (
        <Body overview={overview.data} />
      ) : (
        <SkeletonRows rows={8} columns={3} />
      )}
    </div>
  );
}

function Body({ overview: h }: { overview: HrmsOverview }) {
  const action = useOutboxAction();
  const count = (status: string) => h.counts[status] ?? 0;
  const failed = count('FAILED');
  const held = count('HELD_NOT_CONNECTED');
  const run = (what: 'retry-failed' | 'release-held') => {
    action.mutate(what, {
      onSuccess: n =>
        toast(
          what === 'retry-failed'
            ? `${plural(n, 'push', 'pushes')} queued again`
            : `${plural(n, 'held punch', 'held punches')} released`,
          { type: 'success', logged: true },
        ),
      onError: error => toast(error.message, { type: 'error' }),
    });
  };
  return (
    <>
      <div className="row between wrap gap-8">
        <div>
          <h2>KRZ HRMS</h2>
          <p className="muted small mt-4">
            KRZ Attend sends punches to KRZ HRMS. HRMS owns regularisation, leave and each employee’s own attendance
            view.
          </p>
        </div>
        <Chip tone={h.connection.connected ? 'green' : 'red'} icon={h.connection.connected ? 'check' : 'x'}>
          {h.connection.connected ? 'Connected' : 'Not connected'}
        </Chip>
      </div>
      <div className="set-section">
        <h3>Connection</h3>
        <KeyValueList
          pairs={[
            ['Endpoint', <span className="mono small">{h.connection.endpoint}</span>],
            [
              'API key',
              <span className="muted small" title="HRMS_API_KEY in the server configuration; it is never shown">
                Set on the server
              </span>,
            ],
            ['Status', h.connection.label],
            ['Retries', `${String(h.retries.maxAttempts)} attempts, waiting ${h.retries.backoffSeconds.join(', ')} s`],
            [
              'What is sent',
              h.pushMode === 'EVERY' ? 'Every counted crossing' : 'Check-in, and the final check-out after day close',
            ],
          ]}
        />
        <div className="row gap-8 mt-12">
          <Button
            size="sm"
            icon="refresh"
            disabled={!h.enabled || held === 0}
            pending={action.isPending}
            {...(h.enabled ? {} : { title: 'Available when KRZ HRMS is connected' })}
            onClick={() => {
              run('release-held');
            }}
          >
            Release held punches{held ? ` (${String(held)})` : ''}
          </Button>
        </div>
      </div>
      <div className="set-section">
        <h3>Master data source</h3>
        <ScrollArea className="dt-wrap mt-8" label="HRMS data sources">
          <table className="dt compact">
            <thead>
              <tr>
                <th>Data</th>
                <th>Source now</th>
                <th>Fetch from KRZ HRMS</th>
              </tr>
            </thead>
            <tbody>
              {h.masterData.map(m => (
                <tr key={m.data}>
                  <td>{m.data}</td>
                  <td>
                    <span className="tag">{m.sourceNow}</span>
                  </td>
                  <td>
                    {m.fromHrmsLater ? (
                      <Switch
                        checked={false}
                        disabled
                        label="Coming later"
                        tooltip="Available when the KRZ HRMS integration is ready"
                      />
                    ) : (
                      <span className="muted small">Not applicable</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollArea>
        <p className="help mt-8">Regularisation, leave and each employee’s own attendance view live in KRZ HRMS.</p>
      </div>
      <div className="set-section">
        <h3>Payload format</h3>
        <div className="grid grid-2 mt-8">
          <div>
            <div className="small muted mb-4">Punch</div>
            <ScrollArea as="pre" className="mono xs code-block" label="Punch payload">
              {JSON.stringify(h.punchSample, null, 2)}
            </ScrollArea>
          </div>
          <div>
            <div className="small muted mb-4">Void (correction of a punch already sent)</div>
            <ScrollArea as="pre" className="mono xs code-block" label="Void payload">
              {JSON.stringify(h.voidSample, null, 2)}
            </ScrollArea>
          </div>
        </div>
        <p className="help mt-8">
          Timestamps are IST with offset. <span className="mono">punchId</span> is stable, so a resend never creates a
          duplicate.
        </p>
      </div>
      <div className="set-section">
        <div className="row between wrap gap-8">
          <h3>Outbox</h3>
          <div className="row gap-8">
            {failed > 0 && (
              <Button
                size="sm"
                icon="refresh"
                pending={action.isPending}
                onClick={() => {
                  run('retry-failed');
                }}
              >
                Retry {failed} failed
              </Button>
            )}
          </div>
        </div>
        <div className="row wrap gap-8 mt-8 mb-8">
          {COUNT_ORDER.filter(s => s !== 'HELD_NOT_CONNECTED' || held > 0).map((s, i) => (
            <span key={s} className="row gap-8">
              {i > 0 && <span className="muted">·</span>}
              <HrmsStatusChip status={s} /> <span className="num small">{count(s)}</span>
            </span>
          ))}
        </div>
        <OutboxTable overview={h} />
      </div>
    </>
  );
}

function OutboxTable({ overview }: { overview: HrmsOverview }) {
  const now = useNow(60_000);
  const canView = useCan('people.view');
  const action = useOutboxAction();
  const [open, setOpen] = useState<OutboxEntry | null>(null);
  const attention = NEEDS_ATTENTION.some(s => (overview.counts[s] ?? 0) > 0);
  const filters = useMemo<readonly TableFilter[]>(
    () => [
      {
        key: 'status',
        label: 'Status',
        options: Object.entries(HRMS_STATUS).map(([value, t]) => ({
          value,
          label: t.label,
          count: overview.counts[value] ?? 0,
        })),
        ...(attention ? { defaultValues: NEEDS_ATTENTION } : {}),
      },
      {
        key: 'kind',
        label: 'Kind',
        options: [
          { value: 'PUNCH', label: 'PUNCH' },
          { value: 'VOID', label: 'VOID' },
        ],
      },
      { key: 'day', label: 'Shift day', range: true },
    ],
    [overview.counts, attention],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const days = state.ranges.day ?? { from: '', to: '' };
  const filterQuery = {
    status: state.values.status ?? [],
    kind: state.values.kind ?? [],
    from: days.from,
    to: days.to,
    q: state.q,
  };
  const sort = state.sort ? `${state.sort.key},${state.sort.dir}` : null;
  const result = useOutbox({ ...filterQuery, sort, page: state.page - 1, size: state.size });
  const columns = useMemo<readonly Column<OutboxEntry>[]>(
    () => [
      {
        key: 'created',
        label: 'Created',
        sort: 'created',
        render: o => <span className="nowrap small">{fmtDateTime(o.createdAt)}</span>,
      },
      {
        key: 'punch',
        label: 'Punch',
        sort: 'punch',
        render: o => (
          <>
            <span className="mono small nowrap">{o.punchId}</span>
            <div className="muted xs nowrap">
              {o.kind === 'VOID' ? 'VOID' : o.type.replace('_', '-')}
              {o.kind === 'PUNCH' && o.punchAt ? ` · ${fmtTime(o.punchAt)}` : ''}
            </div>
          </>
        ),
      },
      {
        key: 'emp',
        label: 'Employee',
        sort: 'employee',
        render: o =>
          o.employee ? (
            <>
              {o.employee.name}
              <div className="muted xs">{o.employee.employeeId ?? 'No Employee ID'}</div>
            </>
          ) : (
            '—'
          ),
      },
      {
        key: 'day',
        label: 'Shift day',
        sort: 'day',
        render: o => <span className="nowrap">{o.shiftDay ? fmtDate(o.shiftDay) : '—'}</span>,
      },
      {
        key: 'status',
        label: 'Status',
        sort: 'status',
        render: o => (
          <>
            <HrmsStatusChip status={o.status} />
            {o.lastError && <div className="muted xs">{o.lastError}</div>}
          </>
        ),
      },
      { key: 'att', label: 'Attempts', sort: 'attempts', num: true, render: o => o.attempts },
      {
        key: 'act',
        label: '',
        lock: true,
        render: o =>
          o.status === 'FAILED' ? (
            <Button
              size="sm"
              onClick={() => {
                action.mutate(
                  { retry: o.code },
                  {
                    onSuccess: () => toast(`${o.punchId} queued again`, { type: 'success', logged: true }),
                    onError: error => toast(error.message, { type: 'error' }),
                  },
                );
              }}
            >
              Retry
            </Button>
          ) : o.status === 'BLOCKED_NO_EMPLOYEE_ID' && canView && o.employee ? (
            <ButtonLink
              size="sm"
              className="nowrap"
              to={`/employees/${o.employee.id}`}
              title="Add the Employee ID on the employee’s profile"
            >
              Add ID
            </ButtonLink>
          ) : null,
      },
    ],
    [action, canView],
  );
  return (
    <>
      <DataTable
        id="outbox"
        control={control}
        filters={filters}
        columns={columns}
        rowKey={o => o.code}
        result={result}
        noun="entries"
        noun1="entry"
        searchPlaceholder="Search by employee, punch ID or error"
        empty={{ icon: 'inbox', title: 'Outbox is empty' }}
        onRow={setOpen}
        exportCsv={{
          href: apiUrl('/settings/hrms/outbox/export', { ...filterQuery, sort }),
          fileName: exportFileName('hrms-outbox', now),
          permission: 'events.export',
        }}
      />
      {open && (
        <Drawer
          open
          title={open.punchId}
          sub={term(HRMS_STATUS, open.status)?.label ?? open.statusLabel}
          onClose={() => {
            setOpen(null);
          }}
        >
          <KeyValueList
            pairs={[
              ['Kind', open.kind],
              ['Type', open.type.replace('_', '-')],
              [
                'Employee',
                open.employee ? `${open.employee.name} · ${open.employee.employeeId ?? 'No Employee ID'}` : '—',
              ],
              ['Shift day', open.shiftDay ? fmtDate(open.shiftDay) : '—'],
              ['Status', <HrmsStatusChip status={open.status} />],
              ['Attempts', String(open.attempts)],
              open.lastError ? ['Last error', open.lastError] : null,
              ['Created', fmtDateTime(open.createdAt)],
              open.lastAttemptAt ? ['Last attempt', fmtDateTime(open.lastAttemptAt)] : null,
              open.nextAttemptAt ? ['Next attempt', fmtDateTime(open.nextAttemptAt)] : null,
              open.sentAt ? ['Sent', fmtDateTime(open.sentAt)] : null,
            ]}
          />
          <div className="small muted mt-16 mb-4">Payload</div>
          <ScrollArea as="pre" className="mono xs code-block" label="Payload">
            {JSON.stringify(open.payload, null, 2)}
          </ScrollArea>
        </Drawer>
      )}
    </>
  );
}
