/**
 * The prototype's holiday editor (date, name, type, where it applies; delete) and the CSV import with the server's
 * preview of every row (ready, already there, or why not).
 */
import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useRef, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { ApiError } from '@/api/client';
import {
  useAllLocations,
  useDeleteHoliday,
  useHolidayYear,
  useImportHolidays,
  usePreviewHolidayImport,
  useSaveHoliday,
  type HolidayView,
  type ImportPreview,
} from '@/api/organisation';
import { useConfirm } from '@/components/feedback/ConfirmDialog';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { DateField } from '@/components/form/DateField';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { useCheckOnEveryChange } from '@/lib/forms';
import { addDays, fmtDate, todayDay } from '@/lib/format';
import { plural } from '@/lib/text';
import { HOLIDAY_TYPES, term } from '@/lib/vocabulary';
import { holidaySchema, type HolidayInput, type HolidayValues } from './schemas';
import '@/components/data/DataTable.module.css';
import '@/components/form/Field.module.css';

const SERVER_FIELDS: Record<string, keyof HolidayInput> = {
  date: 'date',
  name: 'name',
  type: 'type',
  locationCode: 'location',
};

/** A public holiday on a day of the last three weeks changes attendance that was already sent. */
const affectsPast = (day: string) => day <= todayDay() && day >= addDays(todayDay(), -21);

export function HolidayEditor({
  holiday: h,
  date,
  onClose,
}: {
  holiday: HolidayView | null;
  date: string;
  onClose: () => void;
}) {
  const isNew = h === null;
  const locations = useAllLocations().data ?? [];
  const year = Number((date || todayDay()).slice(0, 4));
  const sameYear = useHolidayYear(year).data;
  const save = useSaveHoliday();
  const remove = useDeleteHoliday();
  const confirm = useConfirm();
  const schema = useMemo(
    () =>
      holidaySchema((day, location) =>
        (sameYear ?? []).some(x => x.code !== h?.code && x.date === day && (x.location?.code ?? '') === location),
      ),
    [sameYear, h?.code],
  );
  const form = useForm<HolidayInput, unknown, HolidayValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      date,
      name: h?.name ?? '',
      type: h?.type === 'OPTIONAL' ? 'OPTIONAL' : 'PUBLIC',
      location: h?.location?.code ?? '',
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
  const day = useWatch({ control, name: 'date' });
  const type = useWatch({ control, name: 'type' });

  const submit = handleSubmit(v => {
    save.mutate(
      {
        code: h?.code ?? null,
        body: {
          date: v.date,
          name: v.name,
          type: v.type,
          locationCode: v.location || null,
          ...(h ? { version: h.version } : {}),
        },
      },
      {
        onSuccess: saved => {
          onClose();
          toast(isNew ? `${saved.name} added on ${fmtDate(saved.date)}` : `${saved.name} saved`, {
            type: 'success',
            logged: true,
          });
        },
        onError: error => {
          const fields = error instanceof ApiError ? error.fieldErrors.filter(f => f.field in SERVER_FIELDS) : [];
          for (const f of fields) {
            const field = SERVER_FIELDS[f.field];
            if (field) setError(field, { type: 'server', message: f.message });
          }
          toast(fields.length ? 'Fix the highlighted fields' : error.message, { type: 'error' });
        },
      },
    );
  });

  return (
    <Modal
      open
      size="sm"
      title={isNew ? 'Add holiday' : `Edit ${h.name}`}
      onClose={onClose}
      footer={
        <>
          {!isNew && (
            <Button
              variant="danger"
              onClick={() => {
                onClose();
                void confirm({
                  title: `Delete ${h.name}?`,
                  message: `${fmtDate(h.date)} becomes a normal working day.`,
                  confirmText: 'Delete',
                  danger: true,
                }).then(ok => {
                  if (!ok) return;
                  remove.mutate(h.code, {
                    onSuccess: () => toast(`${h.name} deleted`, { type: 'success', logged: true }),
                    onError: error => toast(error.message, { type: 'error' }),
                  });
                });
              }}
            >
              Delete
            </Button>
          )}
          <span className="grow" />
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" pending={save.isPending} onClick={() => void submit()}>
            {isNew ? 'Add holiday' : 'Save'}
          </Button>
        </>
      }
    >
      <form
        className="col"
        noValidate
        onSubmit={event => {
          event.preventDefault();
          void submit();
        }}
      >
        <div className="field">
          <label htmlFor="hd-date">Date</label>
          <Controller
            control={control}
            name="date"
            render={({ field }) => (
              <DateField id="hd-date" value={field.value} label="Holiday date" onChange={field.onChange} />
            )}
          />
          <div className="err-text">{errors.date?.message ?? ''}</div>
        </div>
        <div className="field">
          <label htmlFor="hd-name">Name</label>
          <input id="hd-name" className="input" placeholder="e.g. Deepavali" {...register('name')} />
          <div className="err-text">{errors.name?.message ?? ''}</div>
        </div>
        <div className="field">
          <label htmlFor="hd-type">Type</label>
          <select id="hd-type" className="select" {...register('type')}>
            {Object.entries(HOLIDAY_TYPES).map(([value, t]) => (
              <option key={value} value={value}>
                {t.label}
              </option>
            ))}
          </select>
          <div className="help">Only public holidays change attendance status.</div>
        </div>
        <div className="field">
          <label htmlFor="hd-loc">Applies to</label>
          <select id="hd-loc" className="select" {...register('location')}>
            <option value="">All locations</option>
            {locations.map(l => (
              <option key={l.code} value={l.code}>
                {l.name}
              </option>
            ))}
          </select>
        </div>
        <div className="small">
          {day && affectsPast(day) && type === 'PUBLIC' && (
            <Callout tone="warn" icon="alert">
              This date is in the past. Attendance for that day is re-evaluated and corrected punches are sent to KRZ
              HRMS.
            </Callout>
          )}
        </div>
      </form>
    </Modal>
  );
}

const TEMPLATE = 'Date,Name,Type,Location\n2026-11-01,Deepavali,Public,All\n2026-12-31,New Year’s Eve,Optional,All\n';

export function HolidayImport({ year, onClose }: { year: number; onClose: () => void }) {
  const [text, setText] = useState('');
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const check = usePreviewHolidayImport();
  const run = useImportHolidays();
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // The prototype re-reads the CSV 150 ms after typing stops; the server previews every row.
  const change = (value: string): void => {
    setText(value);
    if (timer.current) clearTimeout(timer.current);
    if (!value.trim()) {
      setPreview(null);
      return;
    }
    timer.current = setTimeout(() => {
      check.mutate(value, { onSuccess: setPreview, onError: error => toast(error.message, { type: 'error' }) });
    }, 150);
  };

  const ready = preview?.ready ?? 0;
  return (
    <Modal
      open
      size="lg"
      title="Import holidays from CSV"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={ready === 0}
            pending={run.isPending}
            onClick={() => {
              run.mutate(text, {
                onSuccess: result => {
                  onClose();
                  toast(
                    `Imported ${plural(result.imported, 'holiday')}${result.skipped ? ` · ${String(result.skipped)} skipped` : ''}`,
                    { type: 'success', logged: true },
                  );
                },
                onError: error => toast(error.message, { type: 'error' }),
              });
            }}
          >
            {ready ? `Import ${plural(ready, 'holiday')}` : 'Import'}
          </Button>
        </>
      }
    >
      <p className="small muted mb-8">
        Columns: <span className="mono">Date, Name, Type, Location</span>. Type is Public or Optional. Location is a
        location name or “All”. Dates like <span className="mono">2026-12-25</span> or{' '}
        <span className="mono">25 Dec 2026</span>.
      </p>
      <div className="row gap-8 mb-8">
        <label className="btn btn-sm">
          <Icon name="upload" size="sm" />
          Choose file
          <input
            type="file"
            accept=".csv,text/csv"
            className="sr-only"
            onChange={event => {
              const file = event.target.files?.[0];
              if (file) void file.text().then(change);
            }}
          />
        </label>
        <a
          className="btn btn-sm btn-ghost"
          download="krz-attend_holidays_template.csv"
          href={`data:text/csv;charset=utf-8,${encodeURIComponent(TEMPLATE)}`}
        >
          <Icon name="download" size="sm" />
          Download template
        </a>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => {
            change(
              `Date,Name,Type,Location\n${String(year)}-11-08,Deepavali,Public,All\n14 Jan ${String(year + 1)},Bhogi,Optional,All\n${String(year)}-12-25,Christmas,Public,All\n31/13/${String(year)},Bad date,Public,All`,
            );
          }}
        >
          Paste an example
        </Button>
      </div>
      <textarea
        className="textarea mono"
        rows={6}
        placeholder={'Date,Name,Type,Location\n2026-11-01,Deepavali,Public,All'}
        aria-label="CSV text"
        value={text}
        onChange={event => {
          change(event.target.value);
        }}
      />
      <div className="mt-12">
        {preview && preview.rows.length > 0 && (
          <>
            <div className="row gap-8 small mb-8">
              <Chip tone="green">{preview.ready} ready</Chip>
              <Chip tone="amber">{preview.duplicates} already exist</Chip>
              <Chip tone="red">{preview.errors} with errors</Chip>
            </div>
            <div style={{ maxHeight: 240, overflow: 'auto' }}>
              <table className="dt compact">
                <thead>
                  <tr>
                    <th>Line</th>
                    <th>Date</th>
                    <th>Name</th>
                    <th>Type</th>
                    <th>Applies to</th>
                    <th>Result</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.rows.map(r => (
                    <tr key={r.line}>
                      <td className="num">{r.line}</td>
                      <td className="nowrap">{r.date ? fmtDate(r.date) : '—'}</td>
                      <td>{r.name ?? ''}</td>
                      <td>{term(HOLIDAY_TYPES, r.type)?.label ?? r.type ?? ''}</td>
                      <td>{r.location?.name ?? 'All'}</td>
                      <td className="small">
                        {r.error ? (
                          <span style={{ color: 'var(--red)' }}>{r.error}</span>
                        ) : r.duplicate ? (
                          'Skipped — already exists'
                        ) : (
                          <span style={{ color: 'var(--green)' }}>Ready</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}
