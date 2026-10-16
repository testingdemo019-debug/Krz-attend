/** The Employees list's bulk actions (the prototype's bulkChangeDepartment and bulkSetTracking). */
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { request } from '@/api/client';
import { useEmployeeBulk, type BulkResult } from '@/api/employees';
import { useAllDepartments } from '@/api/organisation';
import { HrmsBanner } from '@/components/domain/HrmsBanner';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { plural } from '@/lib/text';
import { TRACKING_REASONS } from '@/lib/vocabulary';
import '@/components/form/Field.module.css';

export function BulkDepartmentDialog({
  ids,
  onClose,
  onDone,
}: {
  ids: readonly string[];
  onClose: () => void;
  onDone: () => void;
}) {
  const departments = useAllDepartments().data ?? [];
  const bulk = useEmployeeBulk();
  const [code, setCode] = useState('');
  const chosen = departments.find(d => d.code === code);
  return (
    <Modal
      open
      size="sm"
      title={`Change department for ${plural(ids.length, 'employee')}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={!chosen}
            pending={bulk.isPending}
            onClick={() => {
              if (!chosen) return;
              bulk.mutate(
                { ids: [...ids], action: 'set-department', departmentCode: chosen.code, ...NO_EXTRAS },
                {
                  onSuccess: result => {
                    toast(`${plural(result.changed, 'employee')} moved to ${chosen.name}`, {
                      type: 'success',
                      logged: true,
                    });
                    onDone();
                    onClose();
                  },
                  onError: error => toast(error.message, { type: 'error' }),
                },
              );
            }}
          >
            Change department
          </Button>
        </>
      }
    >
      <HrmsBanner />
      <div className="field">
        <label htmlFor="bulk-dept">New department</label>
        <select
          id="bulk-dept"
          className="select"
          value={code}
          onChange={event => {
            setCode(event.target.value);
          }}
        >
          <option value="">Select…</option>
          {departments
            .filter(d => d.status === 'ACTIVE')
            .map(d => (
              <option key={d.code} value={d.code}>
                {d.name}
              </option>
            ))}
        </select>
      </div>
      <p className="small muted mt-12">
        Their shift follows the new department’s allocation unless they have an individual allocation.
      </p>
    </Modal>
  );
}

/** The fields a bulk request leaves out. */
const NO_EXTRAS = {
  trackingMode: null,
  trackingReason: null,
  shiftCode: null,
  from: null,
  until: null,
  note: null,
  preview: null,
} as const;

type Mode = 'TRACKED' | 'NOT_TRACKED';
type Reason = keyof typeof TRACKING_REASONS;

export function BulkTrackingDialog({
  ids,
  onClose,
  onDone,
}: {
  ids: readonly string[];
  onClose: () => void;
  onDone: () => void;
}) {
  const bulk = useEmployeeBulk();
  const [mode, setMode] = useState<Mode>('TRACKED');
  const [reason, setReason] = useState<Reason>('NO_FACE');
  // The server says who would be skipped before anything changes (the prototype's live check).
  const check = useQuery({
    queryKey: ['employees', 'tracking-check', ids, mode, reason],
    queryFn: ({ signal }) =>
      request<BulkResult>('/employees/bulk', {
        method: 'POST',
        signal,
        body: {
          ids,
          action: 'set-tracking',
          departmentCode: null,
          trackingMode: mode,
          trackingReason: mode === 'TRACKED' ? null : reason,
          shiftCode: null,
          from: null,
          until: null,
          note: null,
          preview: true,
        },
      }),
  });
  const skipped = check.data?.skipped ?? [];
  return (
    <Modal
      open
      size="sm"
      title={`Set attendance tracking for ${plural(ids.length, 'employee')}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            pending={bulk.isPending}
            onClick={() => {
              bulk.mutate(
                {
                  ids: [...ids],
                  action: 'set-tracking',
                  ...NO_EXTRAS,
                  departmentCode: null,
                  trackingMode: mode,
                  trackingReason: mode === 'TRACKED' ? null : reason,
                },
                {
                  onSuccess: result => {
                    toast(`Tracking updated for ${plural(result.changed, 'employee')}`, {
                      type: 'success',
                      logged: true,
                    });
                    onDone();
                    onClose();
                  },
                  onError: error => toast(error.message, { type: 'error' }),
                },
              );
            }}
          >
            Apply
          </Button>
        </>
      }
    >
      <div className="col">
        <label className="check">
          <input
            type="radio"
            name="bt"
            checked={mode === 'TRACKED'}
            onChange={() => {
              setMode('TRACKED');
            }}
          />{' '}
          Tracked by camera
        </label>
        <label className="check">
          <input
            type="radio"
            name="bt"
            checked={mode === 'NOT_TRACKED'}
            onChange={() => {
              setMode('NOT_TRACKED');
            }}
          />{' '}
          Not tracked — HR records attendance in KRZ HRMS
        </label>
        {mode === 'NOT_TRACKED' && (
          <div className="field mt-8">
            <label htmlFor="bt-reason">Reason</label>
            <select
              id="bt-reason"
              className="select"
              value={reason}
              onChange={event => {
                setReason(event.target.value as Reason);
              }}
            >
              {Object.entries(TRACKING_REASONS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="small mt-8">
          {skipped.length > 0 && (
            <Callout tone="warn" icon="alert">
              {plural(skipped.length, 'employee')} will be skipped:{' '}
              {skipped
                .slice(0, 4)
                .map(s => `${s.name} (${s.reason})`)
                .join(', ')}
              {skipped.length > 4 ? '…' : ''}
            </Callout>
          )}
        </div>
      </div>
    </Modal>
  );
}
