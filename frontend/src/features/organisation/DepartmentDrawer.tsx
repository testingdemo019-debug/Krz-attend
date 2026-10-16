/**
 * The prototype's department drawer: details, shift allocations, members; deactivate (moving the members to another
 * department and ending its allocations), reactivate, delete (only if never used) and edit.
 */
import { useState } from 'react';
import { Link } from 'react-router';
import {
  useAllDepartments,
  useArchiveDepartment,
  useDeleteDepartment,
  useDepartment,
  useRestoreDepartment,
  useShifts,
  type DepartmentDetail,
} from '@/api/organisation';
import { useDetailDrawers } from '@/app/DetailDrawers';
import { SourceTag } from '@/components/domain/HrmsBanner';
import { useConfirm } from '@/components/feedback/ConfirmDialog';
import { Drawer } from '@/components/feedback/Drawer';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { Avatar } from '@/components/ui/Avatar';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { Skeleton } from '@/components/ui/Skeleton';
import { fmtDate } from '@/lib/format';
import { plural } from '@/lib/text';
import { ALLOCATION_STATE, RECORD_STATUS, term } from '@/lib/vocabulary';
import '@/components/data/DataTable.module.css';
import '@/components/form/Field.module.css';

export function DepartmentDrawer({ code, onClose, onEdit }: { code: string; onClose: () => void; onEdit: () => void }) {
  const detail = useDepartment(code).data;
  const defaultShift = useShifts().data?.find(s => s.defaultShift);
  const { openAllocation } = useDetailDrawers();
  const confirm = useConfirm();
  const restore = useRestoreDepartment();
  const remove = useDeleteDepartment();
  const [deactivating, setDeactivating] = useState(false);
  const d = detail;
  const active = d?.status === 'ACTIVE';
  const used = d ? !d.deletable : false;

  const footer = d && (
    <>
      {active ? (
        <Button
          icon="ban"
          onClick={() => {
            setDeactivating(true);
          }}
        >
          Deactivate
        </Button>
      ) : (
        <Button
          icon="refresh"
          pending={restore.isPending}
          onClick={() => {
            restore.mutate(d.code, {
              onSuccess: () => {
                onClose();
                toast(`${d.name} reactivated`, { type: 'success', logged: true });
              },
              onError: error => toast(error.message, { type: 'error' }),
            });
          }}
        >
          Reactivate
        </Button>
      )}
      {used ? (
        <Button
          icon="trash"
          disabled
          tooltip={`In use by ${plural(d.members.length, 'employee')} and ${plural(d.allocations.length, 'allocation')} — deactivate it instead`}
        >
          Delete
        </Button>
      ) : (
        <Button
          variant="danger"
          icon="trash"
          onClick={() => {
            void confirm({
              title: `Delete ${d.name}?`,
              message: 'It has never been used, so it can be deleted. This can’t be undone.',
              confirmText: 'Delete department',
              danger: true,
            }).then(ok => {
              if (!ok) return;
              remove.mutate(d.code, {
                onSuccess: () => {
                  onClose();
                  toast(`${d.name} deleted`, { type: 'success', logged: true });
                },
                onError: error => toast(error.message, { type: 'error' }),
              });
            });
          }}
        >
          Delete
        </Button>
      )}
      <span className="grow" />
      <Button variant="primary" icon="edit" onClick={onEdit}>
        Edit
      </Button>
    </>
  );

  return (
    <>
      <Drawer
        open
        wide
        onClose={onClose}
        title={d?.name ?? code}
        sub={d ? `${d.code} · ${term(RECORD_STATUS, d.status)?.label ?? d.status}` : undefined}
        footer={footer ?? undefined}
      >
        {d ? (
          <>
            <KeyValueList
              pairs={[
                ['Code', <span className="mono">{d.code}</span>],
                ['Description', d.description ?? '—'],
                ['Head', d.head?.displayName ?? '—'],
                ['Created', `${fmtDate(d.createdAt)} by ${d.createdBy ?? 'System'}`],
                ['Source', <SourceTag />],
              ]}
            />
            <h3 className="mt-16 mb-8">Shift allocations</h3>
            {d.allocations.length > 0 ? (
              <table className="dt compact">
                <thead>
                  <tr>
                    <th>Shift</th>
                    <th>From</th>
                    <th>Until</th>
                    <th>State</th>
                  </tr>
                </thead>
                <tbody>
                  {d.allocations.map(a => (
                    <tr key={a.code}>
                      <td>{a.shift.name}</td>
                      <td>{fmtDate(a.from)}</td>
                      <td>{a.until ? fmtDate(a.until) : 'Open-ended'}</td>
                      <td>{term(ALLOCATION_STATE, a.state)?.label ?? a.state}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="muted small">
                None — members follow the organisation default shift ({defaultShift?.name ?? '—'}) unless they have an
                individual allocation.
              </p>
            )}
            <Button
              size="sm"
              icon="route"
              className="mt-8"
              onClick={() => {
                onClose();
                openAllocation({ targetType: 'DEPARTMENT', targetIds: [d.code] });
              }}
            >
              Allocate a shift to this department
            </Button>
            <h3 className="mt-16 mb-8">Employees ({d.members.length})</h3>
            {d.members.length > 0 ? (
              <div className="col" style={{ gap: 4, maxHeight: 280, overflow: 'auto' }}>
                {d.members.map(m => (
                  <Link key={m.id} className="sr-item" to={`/employees/${m.id}`}>
                    <Avatar id={m.id} name={m.displayName} size="sm" />
                    <span className="grow">
                      {m.displayName} <span className="muted small">{m.designation ?? ''}</span>
                    </span>
                    {!m.accountEnabled && <Chip tone="grey">Disabled</Chip>}
                  </Link>
                ))}
              </div>
            ) : (
              <p className="muted small">No employees.</p>
            )}
          </>
        ) : (
          <Skeleton className="sk-line" width="60%" />
        )}
      </Drawer>
      {deactivating && d && (
        <DeactivateDialog
          department={d}
          onClose={() => {
            setDeactivating(false);
          }}
          onDone={onClose}
        />
      )}
    </>
  );
}

/** The prototype's dept-deactivate: members move to another active department; its allocations end today. */
function DeactivateDialog({
  department: d,
  onClose,
  onDone,
}: {
  department: DepartmentDetail;
  onClose: () => void;
  onDone: () => void;
}) {
  const others = (useAllDepartments().data ?? []).filter(x => x.code !== d.code && x.status === 'ACTIVE');
  const archive = useArchiveDepartment();
  const [moveTo, setMoveTo] = useState('');
  const members = d.members.length;
  const activeAllocations = d.allocations.filter(a => a.state === 'ACTIVE' || a.state === 'SCHEDULED').length;
  const target = others.find(x => x.code === moveTo);
  return (
    <Modal
      open
      size="sm"
      title={`Deactivate ${d.name}?`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={members > 0 && !moveTo}
            pending={archive.isPending}
            onClick={() => {
              archive.mutate(
                { code: d.code, moveTo: moveTo || null },
                {
                  onSuccess: () => {
                    onClose();
                    onDone();
                    toast(
                      `${d.name} deactivated${members && target ? ` · ${plural(members, 'employee')} moved to ${target.name}` : ''}`,
                      { type: 'success', logged: true },
                    );
                  },
                  onError: error => toast(error.message, { type: 'error' }),
                },
              );
            }}
          >
            Deactivate
          </Button>
        </>
      }
    >
      <p>Inactive departments can’t receive new employees or allocations. History is kept.</p>
      {members > 0 && (
        <div className="field mt-12">
          <label htmlFor="dd-move">Move its {plural(members, 'employee')} to</label>
          <select
            id="dd-move"
            className="select"
            value={moveTo}
            onChange={event => {
              setMoveTo(event.target.value);
            }}
          >
            <option value="">Select a department…</option>
            {others.map(x => (
              <option key={x.code} value={x.code}>
                {x.name}
              </option>
            ))}
          </select>
          <div className="help">Their shift follows the new department unless they have an individual allocation.</div>
        </div>
      )}
      {activeAllocations > 0 && (
        <p className="small mt-12">
          {plural(activeAllocations, 'shift allocation')} for this department will end today.
        </p>
      )}
    </Modal>
  );
}
