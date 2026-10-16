/**
 * The location editor (new in the specification): step 1 basics (name, code, address), step 2 entry points. A new
 * location is created with its entry points in one go; for an existing one, adding, renaming and archiving an entry
 * point apply at once (each is audited) and "Save changes" saves the basics. An entry point with an enabled camera
 * can't be archived, as the server refuses it.
 */
import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { ApiError } from '@/api/client';
import {
  useAddEntryPoint,
  useAllLocations,
  useArchiveEntryPoint,
  useCreateLocation,
  useLocationDetail,
  useRenameEntryPoint,
  useUpdateLocation,
  type LocationView,
} from '@/api/organisation';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { IconButton } from '@/components/ui/IconButton';
import { useCheckOnEveryChange } from '@/lib/forms';
import { plural } from '@/lib/text';
import { locationSchema, type LocationInput, type LocationValues } from './schemas';
import '@/components/form/Field.module.css';

const SERVER_FIELDS: Record<string, keyof LocationInput> = { name: 'name', code: 'code', address: 'address' };

export function LocationEditor({ location, onClose }: { location: LocationView | null; onClose: () => void }) {
  const locationsQuery = useAllLocations();
  // The entry points of an existing location change while the editor is open: follow the server's copy.
  const live = useLocationDetail(location?.code ?? null).data ?? location;
  const isNew = location === null;
  const create = useCreateLocation();
  const update = useUpdateLocation(location?.code ?? '');
  const [step, setStep] = useState(1);
  const [names, setNames] = useState<string[]>([]);
  const [newName, setNewName] = useState('');
  const schema = useMemo(() => {
    const others = (locationsQuery.data ?? []).filter(x => x.code !== location?.code);
    return locationSchema({ names: others.map(x => x.name.toLowerCase()), codes: others.map(x => x.code) });
  }, [locationsQuery.data, location?.code]);
  const form = useForm<LocationInput, unknown, LocationValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: location?.name ?? '', code: location?.code ?? '', address: location?.address ?? '' },
  });
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = form;
  useCheckOnEveryChange(form);

  const serverErrors = (error: Error): void => {
    const fields = error instanceof ApiError ? error.fieldErrors.filter(f => f.field in SERVER_FIELDS) : [];
    for (const f of fields) {
      const field = SERVER_FIELDS[f.field];
      if (field) setError(field, { type: 'server', message: f.message });
    }
    if (fields.length) setStep(1);
    toast(fields.length ? 'Fix the highlighted fields' : error.message, { type: 'error' });
  };

  const save = handleSubmit(v => {
    const basics = { code: v.code, name: v.name, address: v.address || null };
    if (isNew) {
      create.mutate(
        { ...basics, entryPoints: names },
        {
          onSuccess: saved => {
            onClose();
            toast(`${saved.name} created${names.length ? ` with ${plural(names.length, 'entry point')}` : ''}`, {
              type: 'success',
              logged: true,
            });
          },
          onError: serverErrors,
        },
      );
    } else {
      update.mutate(
        { ...basics, version: (live ?? location).version },
        {
          onSuccess: saved => {
            onClose();
            toast(`${saved.name} saved`, { type: 'success', logged: true });
          },
          onError: serverErrors,
        },
      );
    }
  });

  return (
    <Modal
      open
      size="lg"
      title={isNew ? 'New location' : `Edit ${location.name}`}
      sub={
        <>
          Step <b>{step}</b> of 2 · {step === 1 ? 'basics' : 'entry points'}
        </>
      }
      onClose={onClose}
      footer={
        step === 1 ? (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button
              variant="primary"
              onClick={() =>
                void handleSubmit(() => {
                  setStep(2);
                })()
              }
            >
              Next: entry points
              <Icon name="arrowRight" />
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
            <Button variant="primary" pending={create.isPending || update.isPending} onClick={() => void save()}>
              {isNew ? 'Create location' : 'Save changes'}
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
          }}
        >
          <div className="field">
            <label htmlFor="lc-name">Name</label>
            <input id="lc-name" className="input" placeholder="e.g. Chennai Office" {...register('name')} />
            <div className="err-text">{errors.name?.message ?? ''}</div>
          </div>
          <div className="field">
            <label htmlFor="lc-code">Code</label>
            <input
              id="lc-code"
              className="input mono"
              placeholder="CHN"
              maxLength={6}
              aria-describedby="lc-code-h"
              {...register('code')}
            />
            <div className="help" id="lc-code-h">
              2–6 characters, A–Z and 0–9. Used in exports and HRMS mapping.
            </div>
            <div className="err-text">{errors.code?.message ?? ''}</div>
          </div>
          <div className="field full">
            <label htmlFor="lc-address">Address</label>
            <input id="lc-address" className="input" {...register('address')} />
            <div className="err-text">{errors.address?.message ?? ''}</div>
          </div>
        </form>
      ) : (
        <>
          <p className="small muted mb-12">
            Entry points are the doors and gates where cameras are mounted.
            {isNew ? ' Add them now or later.' : ' Changes here are saved at once.'}
          </p>
          {isNew ? <NewEntryPoints names={names} onChange={setNames} /> : live && <EntryPoints location={live} />}
          <form
            className="row gap-8 mt-12"
            onSubmit={event => {
              event.preventDefault();
            }}
          >
            <input
              className="input"
              aria-label="New entry point"
              placeholder="e.g. Main entrance"
              value={newName}
              onChange={event => {
                setNewName(event.target.value);
              }}
            />
            <AddEntryPoint
              code={isNew ? null : location.code}
              name={newName}
              onAdded={name => {
                if (isNew) setNames(list => [...list, name]);
                setNewName('');
              }}
            />
          </form>
        </>
      )}
    </Modal>
  );
}

function AddEntryPoint({
  code,
  name,
  onAdded,
}: {
  code: string | null;
  name: string;
  onAdded: (name: string) => void;
}) {
  const add = useAddEntryPoint(code ?? '');
  const clean = name.trim();
  return (
    <Button
      type="submit"
      icon="plus"
      disabled={clean.length < 2}
      pending={add.isPending}
      onClick={() => {
        if (code === null) {
          onAdded(clean);
          return;
        }
        add.mutate(clean, {
          onSuccess: () => {
            onAdded(clean);
            toast(`${clean} added`, { type: 'success', logged: true });
          },
          onError: error => toast(error.message, { type: 'error' }),
        });
      }}
    >
      Add
    </Button>
  );
}

function NewEntryPoints({ names, onChange }: { names: readonly string[]; onChange: (names: string[]) => void }) {
  if (names.length === 0) return <p className="muted small">No entry points yet.</p>;
  return (
    <div className="col gap-4">
      {names.map((n, i) => (
        <div key={`${n}-${String(i)}`} className="row gap-8">
          <span className="grow">{n}</span>
          <IconButton
            icon="x"
            size="sm"
            label={`Remove ${n}`}
            onClick={() => {
              onChange(names.filter((_, j) => j !== i));
            }}
          />
        </div>
      ))}
    </div>
  );
}

function EntryPoints({ location: l }: { location: LocationView }) {
  const rename = useRenameEntryPoint(l.code);
  const archive = useArchiveEntryPoint(l.code);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  if (l.entryPoints.length === 0) return <p className="muted small">No entry points yet.</p>;
  return (
    <div className="col gap-4">
      {l.entryPoints.map(p => {
        const draft = drafts[p.code] ?? p.name;
        const active = p.status === 'ACTIVE';
        const blocked =
          p.enabledCameras > 0
            ? `${p.name} has ${plural(p.enabledCameras, 'enabled camera')} — move or disable it first.`
            : null;
        return (
          <div key={p.code} className="row gap-8">
            <input
              className="input"
              aria-label={`Name of ${p.name}`}
              value={draft}
              disabled={!active}
              onChange={event => {
                setDrafts(d => ({ ...d, [p.code]: event.target.value }));
              }}
            />
            <span className="mono muted small nowrap">{p.code}</span>
            <span className="muted small nowrap">{plural(p.cameras, 'camera')}</span>
            {active ? (
              <>
                <Button
                  size="sm"
                  disabled={draft.trim() === p.name || draft.trim().length < 2}
                  onClick={() => {
                    rename.mutate(
                      { entryPoint: p.code, name: draft.trim(), version: p.version },
                      {
                        onSuccess: () => toast(`Renamed to ${draft.trim()}`, { type: 'success', logged: true }),
                        onError: error => toast(error.message, { type: 'error' }),
                      },
                    );
                  }}
                >
                  Rename
                </Button>
                <Button
                  size="sm"
                  icon="ban"
                  disabled={blocked !== null}
                  tooltip={blocked ?? undefined}
                  onClick={() => {
                    archive.mutate(p.code, {
                      onSuccess: () => toast(`${p.name} archived`, { type: 'success', logged: true }),
                      onError: error => toast(error.message, { type: 'error' }),
                    });
                  }}
                >
                  Archive
                </Button>
              </>
            ) : (
              <Chip tone="grey">Inactive</Chip>
            )}
          </div>
        );
      })}
    </div>
  );
}
