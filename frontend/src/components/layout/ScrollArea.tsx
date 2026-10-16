/**
 * F6.11: an area that scrolls but has nothing focusable inside (a drawer of read-only details, a wide report table, a
 * JSON payload) can still be scrolled with the keyboard: while that is so it is a named, focusable region.
 */
import { useEffect, useState, type ReactNode } from 'react';

const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/** Set on the areas made focusable here; dialogs leave them out when they focus their first control. */
export const SCROLL_REGION = 'data-scroll-region';

export function ScrollArea({
  as = 'div',
  className,
  label,
  children,
}: {
  as?: 'div' | 'pre';
  className: string;
  /** The region's name while it is focusable. */
  label: string;
  children: ReactNode;
}) {
  const [el, setEl] = useState<HTMLElement | null>(null);
  const [region, setRegion] = useState(false);
  useEffect(() => {
    if (!el) return undefined;
    const update = (): void => {
      const scrolls = el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1;
      setRegion(scrolls && !el.querySelector(FOCUSABLE));
    };
    update();
    const resized = new ResizeObserver(update);
    resized.observe(el);
    const changed = new MutationObserver(update);
    changed.observe(el, { childList: true, subtree: true });
    return () => {
      resized.disconnect();
      changed.disconnect();
    };
  }, [el]);
  const props = {
    ref: setEl,
    className,
    ...(region ? { tabIndex: 0, role: 'region', 'aria-label': label, [SCROLL_REGION]: '' } : {}),
  };
  return as === 'pre' ? <pre {...props}>{children}</pre> : <div {...props}>{children}</div>;
}
