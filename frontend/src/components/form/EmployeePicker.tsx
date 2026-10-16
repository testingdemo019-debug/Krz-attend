/**
 * The prototype's openEmployeePicker: a dialog that searches enabled employees by name, Employee ID, department or
 * designation and picks one. Extra fields (reason, note, template) go in `footer`; the caller decides on a pick
 * whether the dialog closes.
 */
import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react';
import { useEmployeeOptions, type EmployeeListItem } from '@/api/employees';
import { FaceStatusChip } from '@/components/domain/Chips';
import { EmptyState } from '@/components/feedback/EmptyState';
import { Modal } from '@/components/feedback/Modal';
import { Icon } from '@/components/icons/Icon';
import { Avatar } from '@/components/ui/Avatar';
import { Spinner } from '@/components/ui/Spinner';
import { DASH } from '@/lib/format';
import '@/components/data/DataTable.module.css';
import './EmployeePicker.module.css';

export function EmployeePicker({
  open,
  title = 'Choose an employee',
  sub,
  onClose,
  onPick,
  note,
  footer,
  hide,
}: {
  open: boolean;
  title?: string;
  sub?: ReactNode;
  onClose: () => void;
  onPick: (employee: EmployeeListItem) => void;
  /** A line under an employee (e.g. why their crossings are not sent to HRMS). */
  note?: (employee: EmployeeListItem) => ReactNode;
  footer?: ReactNode;
  /** Leaves people out (Give access: those who already have it). */
  hide?: (employee: EmployeeListItem) => boolean;
}) {
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  const employees = useEmployeeOptions(q, open);
  const rows = (employees.data?.items ?? []).filter(e => !hide?.(e));

  return (
    <Modal open={open} onClose={onClose} title={title} sub={sub} size="lg">
      <div className="dt-search" style={{ width: '100%' }}>
        <Icon name="search" size="sm" />
        <input
          className="input"
          placeholder="Search by name, Employee ID, department or designation"
          aria-label="Search employees"
          data-autofocus
          value={text}
          onChange={event => {
            const next = event.target.value;
            setText(next);
            if (timer.current) clearTimeout(timer.current);
            timer.current = setTimeout(() => {
              setQ(next);
            }, 200);
          }}
        />
      </div>
      <div className="mt-12" style={{ maxHeight: '50vh', overflow: 'auto' }}>
        {employees.isPending ? (
          <div className="grid-center" style={{ padding: 24 }}>
            <Spinner dark label="Loading employees" />
          </div>
        ) : rows.length === 0 ? (
          <EmptyState icon="search" title="No employees found" text="Try a name, Employee ID or department." />
        ) : (
          rows.map(e => (
            <Fragment key={e.id}>
              <button
                type="button"
                className="sr-item picker-row"
                onClick={() => {
                  onPick(e);
                }}
              >
                <Avatar id={e.id} name={e.name} />
                <span className="grow">
                  <span style={{ fontWeight: 600 }}>{e.name}</span>{' '}
                  <span className="muted small">{e.employeeId ?? 'No Employee ID'}</span>
                  <br />
                  <span className="muted small">
                    {e.department?.name ?? DASH} · {e.designation ?? DASH}
                  </span>
                </span>
                <FaceStatusChip status={e.faceStatus} />
              </button>
              {note?.(e)}
            </Fragment>
          ))
        )}
      </div>
      {footer}
    </Modal>
  );
}
