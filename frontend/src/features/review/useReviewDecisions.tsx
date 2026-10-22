/**
 * The Review Queue's decisions (R12) as in the prototype: confirm, assign (the employee picker with reason, note and
 * template), not an employee, reject, spoof, bulk; each shows the logged toast with Undo for the server's undo window
 * (F10: undo calls the server). A deciding card fades out until the refreshed list no longer has it.
 */
import { useState } from 'react';
import { ApiError } from '@/api/client';
import type { EmployeeListItem } from '@/api/employees';
import {
  useReviewBulk,
  useReviewDecision,
  useReviewUndo,
  type DecisionRequest,
  type ReviewApplied,
  type ReviewItem,
} from '@/api/review';
import { useConfirm } from '@/components/feedback/ConfirmDialog';
import { toast } from '@/components/feedback/toast';
import { EmployeePicker } from '@/components/form/EmployeePicker';
import { EFFECTS, REVIEW_ACTIONS, term, type ReviewAction } from '@/lib/vocabulary';
import { fmtTime } from '@/lib/format';
import { plural } from '@/lib/text';
import '@/components/form/Field.module.css';
import { bestOf } from './ReviewCard';

export type BulkOp = 'NOT_EMPLOYEE' | 'CONFIRM' | 'REJECT';

export const BULK_LABELS: Record<BulkOp, string> = {
  NOT_EMPLOYEE: 'Not an employee',
  CONFIRM: 'Confirm top candidates',
  REJECT: 'Reject — bad image',
};

/** What the card's controls hold for one item: the chosen best crossing and the template tick. */
export interface CardChoice {
  best: string | null;
  addTemplate: boolean;
}

const firstName = (name: string): string => name.trim().split(/\s+/)[0] ?? name;

/** The prototype's resolutionSummary: " · check-in 09:12, check-out 18:01". */
function summary(applied: ReviewApplied): string {
  const parts = applied.effects.map(
    e => `${(term(EFFECTS, e.effect)?.label ?? e.effect).toLowerCase()} ${fmtTime(e.at)}`,
  );
  return parts.length ? ` · ${parts.join(', ')}` : '';
}

function body(action: ReviewAction, rest: Partial<Omit<DecisionRequest, 'action'>> = {}): DecisionRequest {
  return {
    action,
    employeeId: rest.employeeId ?? null,
    reason: rest.reason ?? null,
    note: rest.note ?? null,
    addTemplate: rest.addTemplate ?? null,
    sightingCode: rest.sightingCode ?? null,
  };
}

export function useReviewDecisions() {
  const decision = useReviewDecision();
  const bulkDecision = useReviewBulk();
  const undo = useReviewUndo();
  const confirm = useConfirm();
  const [leaving, setLeaving] = useState<ReadonlySet<string>>(new Set());
  const [assigning, setAssigning] = useState<{ item: ReviewItem; choice: CardChoice } | null>(null);

  const settle = (code: string): void => {
    setLeaving(current => {
      const next = new Set(current);
      next.delete(code);
      return next;
    });
  };

  /** Sends one decision; `who` names the employee in the toast and in the no-consent warning. */
  const run = (item: ReviewItem, request: DecisionRequest, who: string | null): void => {
    setLeaving(current => new Set(current).add(item.code));
    decision.mutate(
      { code: item.code, body: request },
      {
        onSuccess: applied => {
          toast(`${REVIEW_ACTIONS[request.action]}${who ? ` — ${who}` : ''}${summary(applied)}`, {
            type: 'success',
            logged: true,
            timeout: applied.undoSeconds * 1000,
            action: {
              label: 'Undo',
              run: () => {
                undo.mutate(applied.undoToken, {
                  onSuccess: () => toast(`Undone — ${item.code} is back in the queue`, { type: 'info', logged: true }),
                  onError: error => toast(error.message, { type: 'error' }),
                });
              },
            },
          });
        },
        onError: error => {
          if (error instanceof ApiError && error.code === 'TEMPLATE_NOT_ELIGIBLE' && request.addTemplate && who) {
            // As the prototype: the decision goes ahead without the template, saying why the server refused it.
            toast(`No template was added for ${firstName(who)}: ${error.message}`, { type: 'warn', logged: true });
            run(item, { ...request, addTemplate: false }, who);
            return;
          }
          toast(error.message, { type: 'error' });
        },
        onSettled: () => {
          settle(item.code);
        },
      },
    );
  };

  const top = (item: ReviewItem) => item.candidates[0] ?? null;

  const actions = {
    confirm(item: ReviewItem, choice: CardChoice): void {
      const candidate = top(item);
      if (!candidate) return;
      run(
        item,
        body(item.type === 'LIVENESS' ? 'FALSE_ALARM' : 'CONFIRM', {
          addTemplate: choice.addTemplate,
          sightingCode: choice.best,
        }),
        candidate.name,
      );
    },
    assign(item: ReviewItem, choice: CardChoice): void {
      setAssigning({ item, choice });
    },
    async notEmployee(item: ReviewItem): Promise<void> {
      if (item.type === 'UNKNOWN') {
        run(item, body('NOT_EMPLOYEE', { reason: 'Visitor' }), null);
        return;
      }
      const result = await confirm({
        title: 'Mark as not an employee?',
        message:
          'This overrides the suggested match. The crop is kept for the unknown-face retention period, then purged.',
        confirmText: 'Not an employee',
        reasons: ['Visitor', 'Wrong suggestion', 'Other'],
        note: 'Note',
        notePlaceholder: 'Required for “Other”',
      });
      if (result) run(item, body('NOT_EMPLOYEE', { reason: result.reason, note: result.note || null }), null);
    },
    reject(item: ReviewItem): void {
      run(item, body('REJECT', { reason: 'Bad image' }), null);
    },
    async spoof(item: ReviewItem): Promise<void> {
      const result = await confirm({
        title: 'Confirm spoof attempt?',
        message: 'No punch is created. The evidence is kept with the audit trail and the administrators are alerted.',
        confirmText: 'Confirm spoof attempt',
        danger: true,
        note: 'Note (optional)',
      });
      if (result) run(item, body('SPOOF', { note: result.note || null }), null);
    },
    async bulk(op: BulkOp, items: readonly ReviewItem[], onDone: () => void): Promise<void> {
      const label = BULK_LABELS[op];
      const result = await confirm({
        title: `${label}?`,
        message: `This applies to ${plural(items.length, 'item')}. You can undo for 10 seconds.`,
        confirmText: label,
      });
      if (!result) return;
      bulkDecision.mutate(
        { codes: items.map(i => i.code), action: op, employeeId: null, reason: null },
        {
          onSuccess: applied => {
            onDone();
            toast(`${label} — ${plural(items.length, 'item')}`, {
              type: 'success',
              logged: true,
              timeout: applied.undoSeconds * 1000,
              action: {
                label: 'Undo',
                run: () => {
                  undo.mutate(applied.undoToken, { onError: error => toast(error.message, { type: 'error' }) });
                },
              },
            });
          },
          onError: error => toast(error.message, { type: 'error' }),
        },
      );
    },
  };

  const dialogs = assigning && (
    <AssignDialog
      key={assigning.item.code}
      item={assigning.item}
      choice={assigning.choice}
      onClose={() => {
        setAssigning(null);
      }}
      onAssign={(employee, request) => {
        setAssigning(null);
        run(assigning.item, request, employee.name);
      }}
    />
  );

  return { actions, leaving, dialogs };
}

/** The prototype's review-assign picker: who, the override reason and note, and the template tick. */
function AssignDialog({
  item,
  choice,
  onClose,
  onAssign,
}: {
  item: ReviewItem;
  choice: CardChoice;
  onClose: () => void;
  onAssign: (employee: EmployeeListItem, request: DecisionRequest) => void;
}) {
  const override = item.type !== 'UNKNOWN';
  const best = bestOf(item, choice);
  const eligibility = best?.template ?? { ok: false, why: 'No crop' };
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const [addTemplate, setAddTemplate] = useState(choice.addTemplate && eligibility.ok);
  const count = item.sightings.length;

  return (
    <EmployeePicker
      open
      title={
        item.type === 'UNKNOWN'
          ? `Assign ${item.label ?? 'unknown face'} (${plural(count, 'crossing')})`
          : 'Assign to employee'
      }
      sub={
        item.type === 'UNKNOWN'
          ? 'Every crossing in this cluster goes through the punch rules for the employee you choose (method “Review”).'
          : 'Overrides the system’s suggestion.'
      }
      onClose={onClose}
      note={e =>
        e.trackingMode === 'TRACKED' ? null : (
          <div className="xs muted" style={{ margin: '-4px 0 6px 52px' }}>
            {e.trackingLabel} — crossings are recorded but not sent to HRMS
          </div>
        )
      }
      onPick={employee => {
        if (override && reason === '') {
          toast('Choose a reason for the override first', { type: 'warn' });
          return;
        }
        if (reason === 'Other' && note.trim() === '') {
          toast('Add a note when the reason is “Other”', { type: 'warn' });
          return;
        }
        let template = addTemplate && eligibility.ok;
        if (template && employee.consentStatus !== 'GIVEN') {
          toast(
            `${firstName(employee.name)} has no consent on file, so no template was added. Record consent in Enrolment, then add a template from this crossing in Events.`,
            { type: 'warn', logged: true },
          );
          template = false;
        }
        onAssign(
          employee,
          body(item.type === 'LIVENESS' ? 'FALSE_ALARM' : 'ASSIGN', {
            employeeId: employee.id,
            reason: reason || null,
            note: note.trim() || null,
            addTemplate: template,
            sightingCode: choice.best,
          }),
        );
      }}
      footer={
        <>
          <div className="divider" />
          <div className="form-grid">
            <div className="field">
              <label htmlFor="pk-reason">Reason{override ? ' (required)' : ''}</label>
              <select
                id="pk-reason"
                className="select"
                value={reason}
                onChange={event => {
                  setReason(event.target.value);
                }}
              >
                <option value="">{override ? 'Select a reason…' : 'Optional'}</option>
                <option>Correct identity</option>
                <option>Wrong suggestion</option>
                <option>Other</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="pk-note">Note</label>
              <input
                id="pk-note"
                className="input"
                placeholder="Required for “Other”"
                value={note}
                onChange={event => {
                  setNote(event.target.value);
                }}
              />
            </div>
            <label className={eligibility.ok ? 'check full' : 'check full muted'}>
              <input
                type="checkbox"
                checked={addTemplate}
                disabled={!eligibility.ok}
                onChange={event => {
                  setAddTemplate(event.target.checked);
                }}
              />
              <span>
                Add the best crop as a face template{' '}
                <span className="xs muted">— {eligibility.why}; needs consent on file</span>
              </span>
            </label>
          </div>
        </>
      }
    />
  );
}
