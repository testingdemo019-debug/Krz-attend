/** Global shortcuts as in the prototype ("/" search, "?" shortcuts, page keys such as the Review Queue's J/K/C/A/V/R). */

/** Typing in a field, or a modifier held: shortcuts stay out of the way. */
export function isTyping(event: KeyboardEvent): boolean {
  const target = event.target as HTMLElement | null;
  return (
    Boolean(target?.closest('input,textarea,select,[contenteditable]')) ||
    event.ctrlKey ||
    event.metaKey ||
    event.altKey
  );
}

/** A dialog, drawer or popover is open: page shortcuts wait (the prototype's overlayStack check). */
export function overlayOpen(): boolean {
  return document.querySelector('[role="dialog"]') !== null;
}
