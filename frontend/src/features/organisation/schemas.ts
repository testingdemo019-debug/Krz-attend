/**
 * The organisation editors' checks in the prototype's words (the server checks everything again): the allocation
 * wizard's steps, the department and location editors, the shift wizard and the holiday editor.
 */
import { z } from 'zod';

/** Allocation wizard: one schema per step that has a check (Shift, Who, Dates). */
export const allocationSteps = {
  1: z.object({ shiftCode: z.string().min(1, 'Choose a shift.') }),
  2: z.object({ targetIds: z.array(z.string()).min(1, 'Choose at least one.') }),
  3: z
    .object({ from: z.string().min(1, 'Choose a start date.'), until: z.string() })
    .refine(v => !v.until || v.until >= v.from, { path: ['until'], message: 'Must be on or after the start date.' }),
} as const;

/** The first message per field of a failed check. */
export function messages(result: { success: boolean; error?: z.ZodError }): Record<string, string> {
  const out: Record<string, string> = {};
  for (const issue of result.error?.issues ?? []) {
    const key = String(issue.path[0] ?? '');
    out[key] ??= issue.message;
  }
  return out;
}

export function departmentSchema(taken: { names: readonly string[]; codes: readonly string[] }) {
  return z.object({
    name: z
      .string()
      .trim()
      .min(1, 'Enter a name.')
      .refine(v => !taken.names.includes(v.toLowerCase()), 'Another department has this name.'),
    code: z
      .string()
      .trim()
      .toUpperCase()
      .regex(/^[A-Z0-9]{2,6}$/, 'Use 2–6 capital letters or digits.')
      .refine(v => !taken.codes.includes(v), 'This code is already used.'),
    description: z.string().trim(),
    headId: z.string(),
    shiftCode: z.string(),
  });
}
export type DepartmentInput = z.input<ReturnType<typeof departmentSchema>>;
export type DepartmentValues = z.output<ReturnType<typeof departmentSchema>>;

export function locationSchema(taken: { names: readonly string[]; codes: readonly string[] }) {
  return z.object({
    name: z
      .string()
      .trim()
      .min(1, 'Enter a name.')
      .refine(v => v.length >= 2 && v.length <= 80, 'Use 2–80 characters.')
      .refine(v => !taken.names.includes(v.toLowerCase()), 'Another location has this name.'),
    code: z
      .string()
      .trim()
      .toUpperCase()
      .min(1, 'Enter a code.')
      .regex(/^[A-Z0-9]{2,6}$/, 'Use 2–6 capital letters or digits.')
      .refine(v => !taken.codes.includes(v), 'This code is already used.'),
    address: z.string().trim().max(300, 'Use at most 300 characters.'),
  });
}
export type LocationInput = z.input<ReturnType<typeof locationSchema>>;
export type LocationValues = z.output<ReturnType<typeof locationSchema>>;

/** The length of a shift in hours ("an end before the start ends the next day"). */
export function shiftHours(start: string, end: string): number {
  const m = (hm: string) => {
    const [h = 0, mi = 0] = hm.split(':').map(Number);
    return h * 60 + mi;
  };
  return ((m(end) - m(start) + 1440) % 1440 || 1440) / 60;
}

/** Shift wizard step 1, Basics. */
export function shiftBasicsSchema(taken: { names: readonly string[]; codes: readonly string[] }, editing: boolean) {
  return z
    .object({
      name: z
        .string()
        .trim()
        .min(1, 'Enter a name.')
        .refine(v => !taken.names.includes(v.toLowerCase()), 'Another shift has this name.'),
      code: editing
        ? z.string()
        : z
            .string()
            .trim()
            .toUpperCase()
            .regex(/^[A-Z0-9-]{2,8}$/, 'Use 2–8 capital letters, digits or dashes.')
            .refine(v => !taken.codes.includes(v), 'This code is already used.'),
      start: z.string(),
      end: z.string(),
      breakMinutes: z.number().min(0, 'Between 0 and 120 minutes.').max(120, 'Between 0 and 120 minutes.'),
      weeklyOffs: z.array(z.number()),
    })
    .superRefine((v, ctx) => {
      const len = shiftHours(v.start, v.end);
      const timing: string[] = [];
      if (len < 4) timing.push('A shift must be at least 4 hours long.');
      else if (len > 14) timing.push('A shift can’t be longer than 14 hours.');
      if (v.weeklyOffs.length > 3) timing.push('Choose at most 3 weekly offs.');
      if (timing.length) ctx.addIssue({ code: 'custom', path: ['timing'], message: timing.join(' ') });
    });
}

/** Shift wizard step 2, Rules. */
export function shiftRulesSchema(lengthHours: number) {
  return z
    .object({
      lateGraceMinutes: z.number().min(0, 'Between 0 and 120 minutes.').max(120, 'Between 0 and 120 minutes.'),
      earlyGraceMinutes: z.number().min(0).max(120),
      halfDayBelowHours: z.number(),
      absentBelowHours: z.number(),
      windowBeforeHours: z.number(),
      dayCloseHours: z.number(),
    })
    .superRefine((v, ctx) => {
      if (v.absentBelowHours >= v.halfDayBelowHours) {
        ctx.addIssue({ code: 'custom', path: ['absentBelowHours'], message: 'Must be lower than the half-day limit.' });
      }
      if (v.halfDayBelowHours >= lengthHours) {
        ctx.addIssue({ code: 'custom', path: ['halfDayBelowHours'], message: 'Must be shorter than the shift.' });
      }
      if (v.windowBeforeHours + v.dayCloseHours + lengthHours > 23) {
        ctx.addIssue({
          code: 'custom',
          path: ['dayCloseHours'],
          message: 'Window, shift and day close must fit in 23 hours so shift days don’t overlap.',
        });
      }
    });
}

/** Shift wizard step 3 when editing: the reason and the recalculation confirmation. */
export const shiftVersionSchema = z
  .object({ effectiveFrom: z.string(), note: z.string().trim(), recalc: z.boolean(), today: z.string() })
  .superRefine((v, ctx) => {
    if (!v.note) {
      ctx.addIssue({ code: 'custom', path: ['note'], message: 'Say why the rules change — it goes in the audit log.' });
    }
    if (v.effectiveFrom <= v.today && !v.recalc) {
      ctx.addIssue({
        code: 'custom',
        path: ['recalc'],
        message: 'Tick the box to confirm recalculating past days, or choose a later date.',
      });
    }
  });

export function holidaySchema(taken: (date: string, location: string) => boolean) {
  return z
    .object({
      date: z.string().min(1, 'Choose a date.'),
      name: z.string().trim().min(1, 'Enter a name.'),
      type: z.enum(['PUBLIC', 'OPTIONAL']),
      location: z.string(),
    })
    .superRefine((v, ctx) => {
      if (v.date && taken(v.date, v.location)) {
        ctx.addIssue({
          code: 'custom',
          path: ['date'],
          message: 'There’s already a holiday on this date for these locations.',
        });
      }
    });
}
export type HolidayInput = z.input<ReturnType<typeof holidaySchema>>;
export type HolidayValues = z.output<ReturnType<typeof holidaySchema>>;
