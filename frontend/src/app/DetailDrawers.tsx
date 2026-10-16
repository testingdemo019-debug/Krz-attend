/**
 * The overlays reachable from every page, as in the prototype: the crossing drawer and the day drawer (open-event,
 * the day drawer from Events, Today, the log and profiles) and the shift allocation wizard (from Shift allocation,
 * Employees, a profile, the employment editor, a department and a new shift). A day drawer's "Details" opens the
 * crossing on top of it; a crossing's "Open day timeline" closes it and opens the day. The wizard closes the drawers
 * first, as the prototype's did.
 */
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { DayDrawer } from '@/features/attendance/DayDrawer';
import { EventDrawer } from '@/features/events/EventDrawer';
import { AllocationWizard, type AllocationStart } from '@/features/organisation/AllocationWizard';

interface DetailDrawerApi {
  /** `onClose` runs when this crossing's drawer closes (e.g. Events removes ?id=). */
  openEvent: (code: string, onClose?: () => void) => void;
  openDay: (employeeId: string, day: string) => void;
  openAllocation: (start: AllocationStart) => void;
}

const Context = createContext<DetailDrawerApi | null>(null);

export function useDetailDrawers(): DetailDrawerApi {
  const api = useContext(Context);
  if (!api) throw new Error('useDetailDrawers outside DetailDrawers');
  return api;
}

export function DetailDrawers({ children }: { children: ReactNode }) {
  const [event, setEvent] = useState<{ code: string; onClose: (() => void) | undefined } | null>(null);
  const [day, setDay] = useState<{ employeeId: string; day: string } | null>(null);
  const [allocation, setAllocation] = useState<AllocationStart | null>(null);
  const api = useMemo<DetailDrawerApi>(
    () => ({
      openEvent: (code, onClose) => {
        setEvent({ code, onClose });
      },
      openDay: (employeeId, d) => {
        setDay({ employeeId, day: d });
      },
      openAllocation: start => {
        setEvent(null);
        setDay(null);
        setAllocation(start);
      },
    }),
    [],
  );
  const closeEvent = useCallback(() => {
    event?.onClose?.();
    setEvent(null);
  }, [event]);

  return (
    <Context value={api}>
      {children}
      {day && (
        <DayDrawer
          key={`${day.employeeId}/${day.day}`}
          employeeId={day.employeeId}
          day={day.day}
          onClose={() => {
            setDay(null);
          }}
          onOpenEvent={code => {
            setEvent({ code, onClose: undefined });
          }}
        />
      )}
      {event && (
        <EventDrawer
          key={event.code}
          code={event.code}
          onClose={closeEvent}
          onOpenDay={(employeeId, d) => {
            closeEvent();
            setDay({ employeeId, day: d });
          }}
        />
      )}
      {allocation && (
        <AllocationWizard
          start={allocation}
          onClose={() => {
            setAllocation(null);
          }}
        />
      )}
    </Context>
  );
}
