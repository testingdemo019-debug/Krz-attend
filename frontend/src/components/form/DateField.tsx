/**
 * The prototype's dateFieldHTML and its calendar: a read-only field that shows "12 Oct 2026" and opens a month
 * calendar. Arrows move by day and week, PageUp/PageDown by month, Home/End to the week's ends, Enter picks, Escape
 * closes and returns focus to the field. Values are days ("2026-10-12"); '' means none.
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { cx } from '@/lib/cx';
import { addDays, fmtDate, fmtDay, MONTH_NAMES, todayDay, weekdayOf, type Day } from '@/lib/format';
import './DateField.module.css';

const WIDTH = 272;
const HEIGHT = 300;
const WEEKDAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];
const STEPS: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 };

const pad = (n: number): string => String(n).padStart(2, '0');

/**
 * Dialogs, drawers and popovers listen for Escape before the calendar does: they call this from onEscapeKeyDown and
 * stay open while Escape only closes a calendar inside them.
 */
export function keepOpenForCalendar(event: KeyboardEvent): void {
  if ((event.target as Element | null)?.closest('.df-cal')) event.preventDefault();
}

/** The same day n months later, clamped to the month's length. */
function addMonths(day: Day, n: number): Day {
  const [y = 0, m = 1, d = 1] = day.split('-').map(Number);
  const first = new Date(Date.UTC(y, m - 1 + n, 1));
  const length = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0)).getUTCDate();
  return `${first.getUTCFullYear()}-${pad(first.getUTCMonth() + 1)}-${pad(Math.min(d, length))}`;
}

export function DateField({
  value,
  onChange,
  label = 'Date',
  placeholder = 'Select a date',
  min = '',
  max = '',
  id,
}: {
  value: Day;
  onChange: (value: Day) => void;
  label?: string;
  placeholder?: string;
  min?: Day;
  max?: Day;
  id?: string;
}) {
  const [anchor, setAnchor] = useState<HTMLInputElement | null>(null);
  const close = (refocus: boolean): void => {
    if (refocus) anchor?.focus();
    setAnchor(null);
  };
  return (
    <span className="datefield">
      <input
        type="text"
        className="input df-display"
        readOnly
        value={value ? fmtDate(value) : ''}
        placeholder={placeholder}
        aria-label={label}
        aria-haspopup="dialog"
        id={id}
        onClick={event => {
          const input = event.currentTarget;
          setAnchor(open => (open ? null : input));
        }}
        onKeyDown={event => {
          if (event.key === 'Enter' || event.key === ' ' || event.key === 'ArrowDown') {
            event.preventDefault();
            setAnchor(event.currentTarget);
          }
        }}
      />
      <Icon name="calendar" size="sm" className="df-ic" />
      {anchor && (
        <Calendar
          anchor={anchor}
          value={value}
          min={min}
          max={max}
          onPick={day => {
            onChange(day);
            close(true);
          }}
          onClose={close}
        />
      )}
    </span>
  );
}

function Calendar({
  anchor,
  value,
  min,
  max,
  onPick,
  onClose,
}: {
  anchor: HTMLInputElement;
  value: Day;
  min: Day;
  max: Day;
  onPick: (day: Day) => void;
  onClose: (refocus: boolean) => void;
}) {
  const [focusDay, setFocusDay] = useState<Day>(() => value || todayDay());
  const ref = useRef<HTMLDivElement>(null);
  // Inside a dialog or drawer the calendar joins it, so the dialog's focus trap and outside-click rules include it.
  const [host] = useState<HTMLElement>(() => anchor.closest<HTMLElement>('.modal, .drawer') ?? document.body);

  useLayoutEffect(() => {
    const cal = ref.current;
    if (!cal) return;
    const r = anchor.getBoundingClientRect();
    // A transformed dialog is the containing block of position:fixed children.
    const origin = host === document.body ? { left: 0, top: 0 } : host.getBoundingClientRect();
    const left = Math.min(Math.max(r.left, 8), window.innerWidth - WIDTH - 8);
    const top = r.bottom + HEIGHT + 8 > window.innerHeight ? Math.max(8, r.top - HEIGHT - 6) : r.bottom + 6;
    cal.style.left = `${left - origin.left}px`;
    cal.style.top = `${top - origin.top}px`;
  }, [anchor, host]);

  useEffect(() => {
    ref.current?.querySelector<HTMLButtonElement>(`[data-day="${focusDay}"]`)?.focus();
  }, [focusDay]);

  useEffect(() => {
    const onDown = (event: MouseEvent): void => {
      const target = event.target as Node;
      if (!ref.current?.contains(target) && !anchor.parentElement?.contains(target)) onClose(false);
    };
    document.addEventListener('mousedown', onDown, true);
    return () => {
      document.removeEventListener('mousedown', onDown, true);
    };
  }, [anchor, onClose]);

  const [y = 0, m = 1] = focusDay.split('-').map(Number);
  const first = `${y}-${pad(m)}-01`;
  const lead = (weekdayOf(first) + 6) % 7;
  const length = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const today = todayDay();

  // Keys on the calendar itself, so Escape stops here and never closes a dialog or popover around the field.
  useEffect(() => {
    const cal = ref.current;
    if (!cal) return undefined;
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        onClose(true);
        return;
      }
      if (!(event.target as HTMLElement).closest('[data-day]')) return;
      const step = STEPS[event.key];
      if (step !== undefined) {
        event.preventDefault();
        setFocusDay(day => addDays(day, step));
      } else if (event.key === 'PageUp' || event.key === 'PageDown') {
        event.preventDefault();
        setFocusDay(day => addMonths(day, event.key === 'PageUp' ? -1 : 1));
      } else if (event.key === 'Home') {
        event.preventDefault();
        setFocusDay(day => addDays(day, -((weekdayOf(day) + 6) % 7)));
      } else if (event.key === 'End') {
        event.preventDefault();
        setFocusDay(day => addDays(day, 6 - ((weekdayOf(day) + 6) % 7)));
      }
    };
    cal.addEventListener('keydown', onKeyDown);
    return () => {
      cal.removeEventListener('keydown', onKeyDown);
    };
  }, [onClose]);

  return createPortal(
    <div ref={ref} className="df-cal" role="dialog" aria-label="Choose a date">
      <div className="row between mb-8">
        <IconButton
          icon="chevLeft"
          iconSize="sm"
          size="sm"
          label="Previous month"
          onClick={() => {
            setFocusDay(addMonths(focusDay, -1));
          }}
        />
        <b aria-live="polite">
          {MONTH_NAMES[m - 1]} {y}
        </b>
        <IconButton
          icon="chevRight"
          iconSize="sm"
          size="sm"
          label="Next month"
          onClick={() => {
            setFocusDay(addMonths(focusDay, 1));
          }}
        />
      </div>
      <div className="df-grid">
        {WEEKDAYS.map(d => (
          <span key={d} className="df-dow" aria-hidden="true">
            {d}
          </span>
        ))}
        {Array.from({ length: lead }, (_, i) => (
          <span key={`lead-${String(i)}`} />
        ))}
        {Array.from({ length }, (_, i) => {
          const day = `${y}-${pad(m)}-${pad(i + 1)}`;
          return (
            <button
              key={day}
              type="button"
              className={cx('df-day', day === value && 'sel', day === today && 'today')}
              data-day={day}
              tabIndex={day === focusDay ? 0 : -1}
              disabled={(min !== '' && day < min) || (max !== '' && day > max)}
              aria-label={fmtDay(day)}
              aria-pressed={day === value}
              onClick={() => {
                onPick(day);
              }}
            >
              {i + 1}
            </button>
          );
        })}
      </div>
      <div className="row between mt-8">
        <Button
          size="sm"
          variant="ghost"
          onClick={() => {
            onPick('');
          }}
        >
          Clear
        </Button>
        <Button
          size="sm"
          onClick={() => {
            onPick(today);
          }}
        >
          Today
        </Button>
      </div>
    </div>,
    host,
  );
}
