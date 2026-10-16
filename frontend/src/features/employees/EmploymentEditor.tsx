/**
 * The prototype's employment editor (openEmploymentEditor): Employee ID, department, designation, type, location,
 * manager, dates and attendance tracking, kept in KRZ Attend until KRZ HRMS; the shift that follows from the
 * department. As in the prototype, every change re-checks the whole form; the server checks again (Employee ID format
 * and uniqueness, R5 tracking) and its field errors show under the fields until the next change.
 */
import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { ApiError } from '@/api/client';
import {
  useAllEnabledEmployees,
  useShiftWithDepartment,
  useUpdateEmployment,
  type EmployeeProfile,
} from '@/api/employees';
import { useMeta } from '@/api/me';
import { useAllDepartments, useAllLocations } from '@/api/organisation';
import { useDetailDrawers } from '@/app/DetailDrawers';
import { HrmsBanner } from '@/components/domain/HrmsBanner';
import { Drawer } from '@/components/feedback/Drawer';
import { toast } from '@/components/feedback/toast';
import { DateField } from '@/components/form/DateField';
import { Button } from '@/components/ui/Button';
import { useCheckOnEveryChange } from '@/lib/forms';
import { plural } from '@/lib/text';
import { EMPLOYMENT_TYPES, TRACKING_REASONS } from '@/lib/vocabulary';
import { EMPLOYMENT_FIELDS, employmentSchema, type EmploymentInput, type EmploymentValues } from './schemas';
import '@/components/form/Field.module.css';

const ACTIVE_FACE = new Set(['ENROLLED', 'NEEDS_REENROL']);

export function EmploymentEditor({ profile, onClose }: { profile: EmployeeProfile; onClose: () => void }) {
  const x = profile.employment;
  const name = profile.identity.displayName;
  const departments = useAllDepartments().data ?? [];
  const locationsQuery = useAllLocations();
  const managers = useAllEnabledEmployees(true).data ?? [];
  const idPattern = useMeta(true).data?.employeeIdPattern;
  const save = useUpdateEmployment(profile.id);
  const { openAllocation } = useDetailDrawers();
  const locations = useMemo(() => locationsQuery.data ?? [], [locationsQuery.data]);
  const schema = useMemo(
    () =>
      employmentSchema({
        idPattern,
        locations,
        faceActive: ACTIVE_FACE.has(profile.face.status),
        accountEnabled: profile.identity.accountEnabled,
      }),
    [idPattern, locations, profile.face.status, profile.identity.accountEnabled],
  );
  const form = useForm<EmploymentInput, unknown, EmploymentValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      employeeId: x.employeeId ?? '',
      department: x.department?.code ?? '',
      designation: x.designation ?? '',
      type: (x.employmentType ?? 'EMPLOYEE') as EmploymentInput['type'],
      location: x.location?.code ?? '',
      manager: x.manager?.id ?? '',
      joinDate: x.joinDate ?? '',
      endDate: x.endDate ?? '',
      mode: x.trackingMode === 'TRACKED' ? 'TRACKED' : 'NOT_TRACKED',
      reason: (x.trackingReason ?? 'EXEMPT') as EmploymentInput['reason'],
    },
  });
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = form;
  useCheckOnEveryChange(form);
  const department = useWatch({ control, name: 'department' });
  const mode = useWatch({ control, name: 'mode' });
  const shift = useShiftWithDepartment(profile.id, department).data ?? profile.shiftToday;

  const submit = handleSubmit(
    v => {
      save.mutate(
        {
          employeeId: v.employeeId || null,
          departmentCode: v.department || null,
          designation: v.designation || null,
          employmentType: v.type,
          locationCode: v.location || null,
          managerId: v.manager || null,
          joinDate: v.joinDate || null,
          endDate: v.endDate || null,
          trackingMode: v.mode,
          trackingReason: v.mode === 'TRACKED' ? null : v.reason,
          version: x.version,
        },
        {
          onSuccess: saved => {
            onClose();
            if (saved.changed === 0) toast('No changes to save', { type: 'info' });
            else toast(`Saved ${plural(saved.changed, 'change')} for ${name}`, { type: 'success', logged: true });
          },
          onError: error => {
            const fields = error instanceof ApiError ? error.fieldErrors.filter(f => f.field in EMPLOYMENT_FIELDS) : [];
            if (fields.length === 0) {
              toast(error.message, { type: 'error' });
              return;
            }
            for (const f of fields) {
              const field = EMPLOYMENT_FIELDS[f.field];
              if (field) setError(field, { type: 'server', message: f.message });
            }
            toast('Fix the highlighted fields', { type: 'error' });
          },
        },
      );
    },
    () => toast('Fix the highlighted fields', { type: 'error' }),
  );

  // The prototype keeps a message line under Employee ID, Designation, Contract end and tracking; the server's other
  // field errors (a department or location that went inactive meanwhile) appear only when there is one.
  const err = (key: keyof EmploymentInput) => <div className="err-text">{errors[key]?.message ?? ''}</div>;
  const serverErr = (key: keyof EmploymentInput) => errors[key] !== undefined && err(key);
  return (
    <Drawer
      open
      wide
      onClose={onClose}
      title={`Edit employment — ${name}`}
      sub="Managed in KRZ Attend until KRZ HRMS sync is ready. Every change is audited."
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" pending={save.isPending} onClick={() => void submit()}>
            Save changes
          </Button>
        </>
      }
    >
      <HrmsBanner />
      <form className="form-grid" noValidate onSubmit={event => void submit(event)}>
        <div className="field">
          <label htmlFor="ef-eid">Employee ID</label>
          <input
            id="ef-eid"
            className="input mono"
            placeholder="EMP-1234"
            aria-describedby="ef-eid-h"
            {...register('employeeId')}
          />
          <div className="help" id="ef-eid-h">
            Must match the ID in KRZ HRMS. Without it, punches are blocked.
          </div>
          {err('employeeId')}
        </div>
        <div className="field">
          <label htmlFor="ef-dept">Department</label>
          <select id="ef-dept" className="select" {...register('department')}>
            {x.department === null && <option value="">Select…</option>}
            {departments
              .filter(d => d.status === 'ACTIVE' || d.code === x.department?.code)
              .map(d => (
                <option key={d.code} value={d.code}>
                  {d.name}
                </option>
              ))}
          </select>
          {serverErr('department')}
        </div>
        <div className="field">
          <label htmlFor="ef-desig">Designation</label>
          <input id="ef-desig" className="input" {...register('designation')} />
          {err('designation')}
        </div>
        <div className="field">
          <label htmlFor="ef-type">Employment type</label>
          <select id="ef-type" className="select" {...register('type')}>
            {Object.entries(EMPLOYMENT_TYPES).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="ef-loc">Location</label>
          <select id="ef-loc" className="select" {...register('location')}>
            {x.location === null && <option value="">Select…</option>}
            {locations.map(l => (
              <option key={l.code} value={l.code}>
                {l.name}
                {l.hasCamera ? '' : ' (no camera)'}
              </option>
            ))}
          </select>
          {serverErr('location')}
        </div>
        <div className="field">
          <label htmlFor="ef-mgr">Manager</label>
          <select id="ef-mgr" className="select" {...register('manager')}>
            <option value="">None</option>
            {managers
              .filter(m => m.id !== profile.id)
              .map(m => (
                <option key={m.id} value={m.id}>
                  {m.name}
                </option>
              ))}
          </select>
          {serverErr('manager')}
        </div>
        <div className="field">
          <label htmlFor="ef-join">Join date</label>
          <Controller
            control={control}
            name="joinDate"
            render={({ field }) => (
              <DateField id="ef-join" value={field.value} label="Join date" onChange={field.onChange} />
            )}
          />
          {serverErr('joinDate')}
        </div>
        <div className="field">
          <label htmlFor="ef-end">Contract end date</label>
          <Controller
            control={control}
            name="endDate"
            render={({ field }) => (
              <DateField
                id="ef-end"
                value={field.value}
                label="Contract end date"
                placeholder="Not set"
                onChange={field.onChange}
              />
            )}
          />
          {err('endDate')}
        </div>
        <fieldset className="field full" style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className="small" style={{ fontWeight: 600, marginBottom: 6 }}>
            Attendance tracking
          </legend>
          <div className="row wrap gap-16">
            <label className="check">
              <input type="radio" value="TRACKED" {...register('mode')} /> Tracked by camera
            </label>
            <label className="check">
              <input type="radio" value="NOT_TRACKED" {...register('mode')} /> Not tracked
            </label>
            <select
              className="select"
              style={{ width: 240 }}
              aria-label="Reason not tracked"
              disabled={mode === 'TRACKED'}
              {...register('reason')}
            >
              {Object.entries(TRACKING_REASONS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          <div className="err-text">{errors.mode?.message ?? errors.reason?.message ?? ''}</div>
        </fieldset>
        <div className="field full">
          <span className="label">Shift</span>
          <div className="small">
            {shift && (
              <span className="row gap-4">
                <span className="sw" style={{ background: shift.colour }} />
                <b>{shift.name}</b> · {shift.timing} <span className="muted">— {shift.source}</span>
              </span>
            )}
            <Button
              size="sm"
              variant="ghost"
              className="mt-4"
              onClick={() => {
                onClose();
                openAllocation({ targetType: 'EMPLOYEE', targetIds: [profile.id] });
              }}
            >
              Allocate a different shift
            </Button>
          </div>
        </div>
      </form>
    </Drawer>
  );
}
