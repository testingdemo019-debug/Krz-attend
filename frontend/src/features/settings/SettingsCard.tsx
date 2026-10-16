/** The building blocks of a settings page: the prototype's draftPage card and its settingField. */
import type { ReactNode } from 'react';
import { UnsavedChangesBar } from '@/components/feedback/UnsavedChangesBar';
import { cx } from '@/lib/cx';
import { UnsavedChangesGuard, type Draft } from '@/lib/useDraft';
import '@/components/form/Field.module.css';
import '@/components/layout/SettingsLayout.module.css';

/** The page's card: title, sub-line, the body and, for an editable page, the save bar and the leave guard. */
export function SettingsCard<T>({
  title,
  sub,
  draft,
  barStart,
  children,
}: {
  title: string;
  sub?: ReactNode;
  draft?: Draft<T>;
  /** A page action at the save bar's left end. */
  barStart?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="card pad">
      <h2>{title}</h2>
      {sub !== undefined && <p className="muted small mt-4">{sub}</p>}
      <div className="mt-12">{children}</div>
      {draft && (
        <>
          <UnsavedChangesBar draft={draft} start={barStart} />
          <UnsavedChangesGuard draft={draft} />
        </>
      )}
    </div>
  );
}

/** The prototype's settingField: a label, the control and an optional help line (a group of controls gets a plain heading). */
export function SettingField({
  label,
  htmlFor,
  help,
  full = false,
  children,
}: {
  label: ReactNode;
  htmlFor?: string;
  help?: ReactNode;
  full?: boolean;
  children: ReactNode;
}) {
  return (
    <div className={cx('field', full && 'full')}>
      {htmlFor ? <label htmlFor={htmlFor}>{label}</label> : <div className="label">{label}</div>}
      {children}
      {help !== undefined && <div className="help">{help}</div>}
    </div>
  );
}

/** The prototype's calibrationTag: a starting value to confirm on the entrance's own footage. */
export function CalibrationTag() {
  return (
    <span className="tag" title="Starting value — confirm after calibrating on your own entrance footage">
      Set after calibration
    </span>
  );
}
