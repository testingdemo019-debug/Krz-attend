import type { ReactNode } from 'react';
import { Chip } from '@/components/ui/Chip';
import { Avatar } from '@/components/ui/Avatar';
import './PersonCell.module.css';

/** The prototype's employeeIdHTML: the HRMS Employee ID, or the amber "No Employee ID" chip. */
export function EmployeeIdText({ employeeId }: { employeeId: string | null }) {
  return employeeId ? (
    <span className="mono">{employeeId}</span>
  ) : (
    <Chip tone="amber" icon="alert" title="Punches can’t be sent to HRMS">
      No Employee ID
    </Chip>
  );
}

/** The prototype's personCellHTML, the same in every table (F6.2): avatar, name and a second line. */
export function PersonCell({ id, name, sub }: { id: string; name: string; sub: ReactNode }) {
  return (
    <div className="cell-person">
      <Avatar id={id} name={name} size="sm" />
      <div style={{ minWidth: 0 }}>
        <div className="nm ellipsis">{name}</div>
        <div className="sub ellipsis">{sub}</div>
      </div>
    </div>
  );
}
