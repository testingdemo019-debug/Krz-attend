/** Shared react-hook-form behaviour of the prototype's editors. */
import { useEffect } from 'react';
import type { FieldValues, UseFormReturn } from 'react-hook-form';

/**
 * The prototype's `el.addEventListener('input' | 'change', validate)`: a change to any field re-checks every field,
 * so a cross-field message (an end date before the start) appears as soon as it applies. The check's own state
 * updates carry no field name, so they don't start another.
 */
export function useCheckOnEveryChange<T extends FieldValues, C, O>(form: UseFormReturn<T, C, O>): void {
  const { subscribe, trigger } = form;
  useEffect(
    () =>
      subscribe({
        formState: { values: true },
        callback: ({ name }) => {
          if (name) void trigger();
        },
      }),
    [subscribe, trigger],
  );
}
