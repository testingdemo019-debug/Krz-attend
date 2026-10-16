/**
 * The prototype's confirmDialog as a promise: `const result = await confirm({...})` resolves to {ok, reason, note}
 * or null when cancelled. Optional reason list (required unless reasonRequired is false; "Other" then needs a note),
 * a note field, and the typed confirmation ("Type ERASE to confirm").
 */
import { createContext, useCallback, useContext, useId, useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/Button';
import { Modal } from './Modal';
import '@/components/form/Field.module.css';

export interface ConfirmOptions {
  title: string;
  message?: ReactNode;
  confirmText?: string;
  danger?: boolean;
  reasons?: readonly string[];
  reasonRequired?: boolean;
  /** Label of an optional note field. */
  note?: string;
  notePlaceholder?: string;
  /** The word to type, e.g. "ERASE". */
  typed?: string;
  extra?: ReactNode;
}

export interface ConfirmResult {
  ok: true;
  reason: string;
  note: string;
}

type Confirm = (options: ConfirmOptions) => Promise<ConfirmResult | null>;
const ConfirmContext = createContext<Confirm | null>(null);

export function useConfirm(): Confirm {
  const confirm = useContext(ConfirmContext);
  if (!confirm) throw new Error('useConfirm outside ConfirmHost');
  return confirm;
}

interface Pending {
  options: ConfirmOptions;
  resolve: (result: ConfirmResult | null) => void;
}

export function ConfirmHost({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const confirm = useCallback<Confirm>(
    options =>
      new Promise(resolve => {
        setPending({ options, resolve });
      }),
    [],
  );
  const finish = (result: ConfirmResult | null): void => {
    pending?.resolve(result);
    setPending(null);
  };
  return (
    <ConfirmContext value={confirm}>
      {children}
      {pending && <ConfirmDialog key={pending.options.title} options={pending.options} onFinish={finish} />}
    </ConfirmContext>
  );
}

function ConfirmDialog({
  options: o,
  onFinish,
}: {
  options: ConfirmOptions;
  onFinish: (r: ConfirmResult | null) => void;
}) {
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const [typed, setTyped] = useState('');
  const id = useId();
  const reasonRequired = o.reasonRequired !== false;
  const valid =
    (!o.typed || typed.trim() === o.typed) &&
    (!o.reasons || !reasonRequired || reason !== '') &&
    (!o.reasons || reason !== 'Other' || !o.note || note.trim().length > 2);
  return (
    <Modal
      open
      size="sm"
      title={o.title}
      onClose={() => {
        onFinish(null);
      }}
      footer={
        <>
          <Button
            onClick={() => {
              onFinish(null);
            }}
          >
            Cancel
          </Button>
          <Button
            variant={o.danger ? 'danger' : 'primary'}
            disabled={!valid}
            onClick={() => {
              onFinish({ ok: true, reason, note });
            }}
          >
            {o.confirmText ?? 'Confirm'}
          </Button>
        </>
      }
    >
      <p>{o.message}</p>
      {o.reasons && (
        <div className="field mt-12">
          <label htmlFor={`${id}-reason`}>Reason{reasonRequired ? ' (required)' : ''}</label>
          <select
            id={`${id}-reason`}
            className="select"
            value={reason}
            onChange={e => {
              setReason(e.target.value);
            }}
          >
            <option value="">Select a reason…</option>
            {o.reasons.map(r => (
              <option key={r}>{r}</option>
            ))}
          </select>
        </div>
      )}
      {o.note && (
        <div className="field mt-12">
          <label htmlFor={`${id}-note`}>{o.note}</label>
          <textarea
            id={`${id}-note`}
            className="textarea"
            rows={3}
            placeholder={o.notePlaceholder ?? ''}
            value={note}
            onChange={e => {
              setNote(e.target.value);
            }}
          />
        </div>
      )}
      {o.typed && (
        <div className="field mt-12">
          <label htmlFor={`${id}-typed`}>
            Type <b className="mono">{o.typed}</b> to confirm
          </label>
          <input
            id={`${id}-typed`}
            className="input"
            autoComplete="off"
            spellCheck={false}
            value={typed}
            onChange={e => {
              setTyped(e.target.value);
            }}
          />
        </div>
      )}
      {o.extra}
    </Modal>
  );
}
