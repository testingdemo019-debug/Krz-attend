/**
 * The prototype's event-correct flow (R11): the void or flip dialog with its reasons and the day before and after
 * (the server's preview), then the logged toast with Undo for the server's undo window.
 */
import { useCorrection, useCorrectionPreview, useCorrectionUndo, type CorrectionKind } from '@/api/crossings';
import { AttendanceStatusChip } from '@/components/domain/Chips';
import { useConfirm } from '@/components/feedback/ConfirmDialog';
import { toast } from '@/components/feedback/toast';
import { Callout } from '@/components/ui/Callout';
import { Spinner } from '@/components/ui/Spinner';
import { DASH, fmtDate, fmtTime } from '@/lib/format';

export interface CorrectionTarget {
  code: string;
  direction: string;
  at: string;
  employeeName: string;
}

const REASONS: Record<CorrectionKind, readonly string[]> = {
  VOID: ['Wrong person identified', 'Test or demo crossing', 'Visitor walked with the employee', 'Other'],
  FLIP: ['Camera read the direction wrong', 'Turned around at the door', 'Other'],
};

const other = (direction: string): string => (direction === 'IN' ? 'OUT' : 'IN');

function BeforeAfter({ code, kind }: { code: string; kind: CorrectionKind }) {
  const preview = useCorrectionPreview(code, kind);
  if (preview.isPending) {
    return (
      <Callout tone="neutral" icon={null} className="mt-12 small">
        <Spinner size="sm" dark label="Working out the day after the change" />
      </Callout>
    );
  }
  if (preview.isError) {
    return (
      <Callout tone="warn" className="mt-12 small">
        {preview.error.message}
      </Callout>
    );
  }
  const day = (d: typeof preview.data.before) => (
    <>
      <AttendanceStatusChip status={d.status} tentative={!d.finalised} />{' '}
      <span className="small muted">
        In {d.checkIn ? fmtTime(d.checkIn) : DASH} · Out {d.checkOut ? fmtTime(d.checkOut) : DASH}
      </span>
    </>
  );
  return (
    <Callout tone="neutral" icon={null} className="mt-12 small">
      <div>
        <b>Before:</b> {day(preview.data.before)}
      </div>
      <div className="mt-4">
        <b>After:</b> {day(preview.data.after)}
      </div>
      {preview.data.alreadySent && (
        <div className="mt-8">Punches already sent to KRZ HRMS for this day will be voided and re-sent.</div>
      )}
    </Callout>
  );
}

export function useCorrectionFlow() {
  const confirm = useConfirm();
  const correction = useCorrection();
  const undo = useCorrectionUndo();

  return async (target: CorrectionTarget, kind: CorrectionKind): Promise<void> => {
    const flipTo = other(target.direction);
    const result = await confirm({
      title: kind === 'VOID' ? 'Void this punch?' : `Change direction to ${flipTo}?`,
      message:
        kind === 'VOID' ? (
          <>
            The {target.direction} at <b>{fmtTime(target.at, true)}</b> on {fmtDate(target.at)} will no longer count for{' '}
            <b>{target.employeeName}</b>. The crossing stays in the log, marked as voided.
          </>
        ) : (
          <>
            The crossing at <b>{fmtTime(target.at, true)}</b> will count as <b>{flipTo}</b> for{' '}
            <b>{target.employeeName}</b>.
          </>
        ),
      reasons: REASONS[kind],
      note: 'Note (required for Other)',
      notePlaceholder: 'What happened?',
      extra: <BeforeAfter code={target.code} kind={kind} />,
      confirmText: kind === 'VOID' ? 'Void punch' : 'Change direction',
      danger: kind === 'VOID',
    });
    if (!result) return;
    const note = result.note.trim();
    const reason = result.reason === 'Other' ? note : `${result.reason}${note ? ` — ${note}` : ''}`;
    correction.mutate(
      { code: target.code, kind, reason },
      {
        onSuccess: applied => {
          toast(
            kind === 'VOID'
              ? `Punch voided for ${target.employeeName}. Day re-evaluated.`
              : `Direction changed to ${flipTo} for ${target.employeeName}. Day re-evaluated.`,
            {
              type: 'success',
              logged: true,
              timeout: applied.undoSeconds * 1000,
              action: {
                label: 'Undo',
                run: () => {
                  undo.mutate(applied.undoToken, {
                    onSuccess: () => toast('Correction undone', { type: 'info', logged: true }),
                    onError: error => toast(error.message, { type: 'error' }),
                  });
                },
              },
            },
          );
        },
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };
}
