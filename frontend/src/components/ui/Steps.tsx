import { Icon } from '@/components/icons/Icon';
import './Steps.module.css';

/**
 * The prototype's wizard steps: numbered, done ones ticked, the current one highlighted and aria-current. The
 * enrolment wizard shows them as a side "stepper" (a nav); the allocation and shift wizards as a row on top.
 */
export function Steps({
  names,
  step,
  allDone = false,
  className,
  label,
  list = false,
}: {
  names: readonly string[];
  /** 1-based. */
  step: number;
  allDone?: boolean;
  className: string;
  /** Given for the side stepper, which is a nav. */
  label?: string;
  /** The shift wizard marks its steps up as a list. */
  list?: boolean;
}) {
  const items = names.map((name, i) => {
    const k = i + 1;
    const state = allDone || k < step ? 'done' : k === step ? 'now' : '';
    return (
      <span
        key={name}
        className={`st ${state}`}
        role={list ? 'listitem' : undefined}
        aria-current={k === step ? 'step' : undefined}
      >
        <span className="st-n">{state === 'done' ? <Icon name="check" size="sm" /> : k}</span>
        <span className="st-label">{name}</span>
      </span>
    );
  });
  return label ? (
    <nav className={className} aria-label={label}>
      {items}
    </nav>
  ) : (
    <div className={className} role={list ? 'list' : undefined}>
      {items}
    </div>
  );
}
