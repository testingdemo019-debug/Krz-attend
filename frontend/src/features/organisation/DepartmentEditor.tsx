/**
 * The prototype's openDepartmentEditor: name, code, description, head and (new only) the default shift; a new
 * department's second step moves employees into it. Every change re-checks the details, as the prototype did.
 */
import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { ApiError } from '@/api/client';
import { useAllEnabledEmployees } from '@/api/employees';
import {
  useAllDepartments,
  useCreateDepartment,
  useDepartment,
  shiftChoices,
  useShifts,
  useUpdateDepartment,
  type DepartmentDetail,
} from '@/api/organisation';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { MultiSelect } from '@/components/form/MultiSelect';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { useCheckOnEveryChange } from '@/lib/forms';
import { plural } from '@/lib/text';
import { departmentSchema, type DepartmentInput, type DepartmentValues } from './schemas';
import '@/components/form/Field.module.css';

const SERVER_FIELDS: Record<string, keyof DepartmentInput> = {
  name: 'name',
  code: 'code',
  description: 'description',
  headId: 'headId',
};

export function DepartmentEditor({ code, onClose }: { code: string | null; onClose: () => void }) {
  const detail = useDepartment(code).data;
  if (code !== null && !detail) return null;
  return <Editor detail={detail ?? null} onClose={onClose} />;
}

function Editor({ detail: d, onClose }: { detail: DepartmentDetail | null; onClose: () => void }) {
  const isNew = d === null;
  const departmentsQuery = useAllDepartments();
  const employees = useAllEnabledEmployees(true).data ?? [];
  const shifts = useShifts().data ?? [];
  const defaultShift = shifts.find(s => s.defaultShift);
  const create = useCreateDepartment();
  const update = useUpdateDepartment(d?.code ?? '');
  const [step, setStep] = useState(1);
  const [members, setMembers] = useState<Set<string>>(new Set());
  const schema = useMemo(() => {
    const others = (departmentsQuery.data ?? []).filter(x => x.code !== d?.code);
    return departmentSchema({ names: others.map(x => x.name.toLowerCase()), codes: others.map(x => x.code) });
  }, [departmentsQuery.data, d?.code]);
  const form = useForm<DepartmentInput, unknown, DepartmentValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: d?.name ?? '',
      code: d?.code ?? '',
      description: d?.description ?? '',
      headId: d?.head?.id ?? '',
      shiftCode: '',
    },
  });
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = form;
  useCheckOnEveryChange(form);
  const name = useWatch({ control, name: 'name' });
  const headId = useWatch({ control, name: 'headId' });

  const heads = isNew
    ? employees
    : employees.filter(e => e.department?.code === d.code).sort((a, b) => a.name.localeCompare(b.name));
  // A new department's head moves in with it.
  const moving = new Set(members);
  if (isNew && headId) moving.add(headId);
  const byDepartment = new Map<string, number>();
  for (const id of moving) {
    const from = employees.find(e => e.id === id)?.department?.name ?? 'no department';
    byDepartment.set(from, (byDepartment.get(from) ?? 0) + 1);
  }

  const serverErrors = (error: Error): void => {
    const fields = error instanceof ApiError ? error.fieldErrors.filter(f => f.field in SERVER_FIELDS) : [];
    if (fields.length === 0) {
      toast(error.message, { type: 'error' });
      return;
    }
    for (const f of fields) {
      const field = SERVER_FIELDS[f.field];
      if (field) setError(field, { type: 'server', message: f.message });
    }
    setStep(1);
    toast('Fix the highlighted fields', { type: 'error' });
  };

  const save = (v: DepartmentValues): void => {
    if (isNew) {
      create.mutate(
        {
          code: v.code,
          name: v.name,
          description: v.description || null,
          headId: v.headId || null,
          shiftCode: v.shiftCode || null,
          memberIds: [...moving],
        },
        {
          onSuccess: saved => {
            onClose();
            toast(`${saved.name} created${moving.size ? ` with ${plural(moving.size, 'employee')}` : ''}`, {
              type: 'success',
              logged: true,
            });
          },
          onError: serverErrors,
        },
      );
    } else {
      update.mutate(
        {
          code: v.code,
          name: v.name,
          description: v.description || null,
          headId: v.headId || null,
          version: d.version,
        },
        {
          onSuccess: saved => {
            onClose();
            toast(`${saved.name} saved`, { type: 'success', logged: true });
          },
          onError: serverErrors,
        },
      );
    }
  };
  const next = handleSubmit(v => {
    if (isNew) setStep(2);
    else save(v);
  });

  return (
    <Modal
      open
      size="lg"
      title={isNew ? 'New department' : `Edit ${d.name}`}
      {...(isNew
        ? {
            sub: (
              <>
                Step <b>{step}</b> of 2 · {step === 1 ? 'details' : 'add employees'}
              </>
            ),
          }
        : {})}
      onClose={onClose}
      footer={
        step === 1 ? (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button variant="primary" pending={update.isPending} onClick={() => void next()}>
              {isNew ? (
                <>
                  Next: add employees
                  <Icon name="arrowRight" />
                </>
              ) : (
                'Save changes'
              )}
            </Button>
          </>
        ) : (
          <>
            <Button
              icon="arrowLeft"
              onClick={() => {
                setStep(1);
              }}
            >
              Back
            </Button>
            <span className="grow" />
            <Button variant="primary" pending={create.isPending} onClick={() => void handleSubmit(save)()}>
              {moving.size ? `Create and move ${plural(moving.size, 'employee')}` : 'Create department'}
            </Button>
          </>
        )
      }
    >
      {step === 1 ? (
        <form
          className="form-grid"
          noValidate
          onSubmit={event => {
            event.preventDefault();
            void next();
          }}
        >
          <div className="field">
            <label htmlFor="dp-name">Name</label>
            <input id="dp-name" className="input" placeholder="e.g. Quality Assurance" {...register('name')} />
            <div className="err-text">{errors.name?.message ?? ''}</div>
          </div>
          <div className="field">
            <label htmlFor="dp-code">Code</label>
            <input
              id="dp-code"
              className="input mono"
              placeholder="QA"
              maxLength={6}
              aria-describedby="dp-code-h"
              {...register('code')}
            />
            <div className="help" id="dp-code-h">
              2–6 characters, A–Z and 0–9. Used in exports and HRMS mapping.
            </div>
            <div className="err-text">{errors.code?.message ?? ''}</div>
          </div>
          <div className="field full">
            <label htmlFor="dp-desc">Description</label>
            <input id="dp-desc" className="input" {...register('description')} />
          </div>
          <div className="field">
            <label htmlFor="dp-head">Head of department</label>
            <select id="dp-head" className="select" {...register('headId')}>
              <option value="">None</option>
              {heads.map(e => (
                <option key={e.id} value={e.id}>
                  {e.name}
                  {isNew ? ` · ${e.department?.name ?? '—'}` : ''}
                </option>
              ))}
            </select>
            <div className="help">
              {isNew ? 'Must be a member — they move into this department.' : 'Must be a member of this department.'}
            </div>
            <div className="err-text">{errors.headId?.message ?? ''}</div>
          </div>
          {isNew ? (
            <div className="field">
              <label htmlFor="dp-shift">Default shift</label>
              <select id="dp-shift" className="select" {...register('shiftCode')}>
                <option value="">Organisation default ({defaultShift?.name ?? '—'})</option>
                {shiftChoices(shifts).map(s => (
                  <option key={s.code} value={s.code}>
                    {s.name} · {s.today.timing}
                  </option>
                ))}
              </select>
              <div className="help">Creates a department allocation from today.</div>
            </div>
          ) : (
            <div className="field">
              <span className="label">Default shift</span>
              <div className="small">
                {d.shift.name}
                {d.shiftSince ? '' : ' (organisation default)'}
              </div>
              <div className="help">Change it with Shift allocation so the date is recorded.</div>
            </div>
          )}
        </form>
      ) : (
        <>
          <p className="small muted mb-12">
            Optional. Selected employees move to <b>{name}</b> now. You can also do this later from Employees.
          </p>
          <MultiSelect
            items={[...employees]
              .sort((a, b) => a.name.localeCompare(b.name))
              .map(e => ({
                id: e.id,
                label: e.name,
                sub: `${e.department?.name ?? '—'} · ${e.designation ?? '—'}`,
                search: `${e.name} ${e.department?.name ?? ''} ${e.employeeId ?? ''}`,
              }))}
            selected={moving}
            onChange={setMembers}
            searchPlaceholder="Search employees to add"
          />
          {moving.size > 0 && (
            <Callout tone="neutral" icon="users" className="mt-8 small">
              <b>{plural(moving.size, 'employee')}</b> will move:{' '}
              {[...byDepartment.entries()].map(([from, n]) => `${String(n)} from ${from}`).join(', ')}.
            </Callout>
          )}
        </>
      )}
    </Modal>
  );
}
