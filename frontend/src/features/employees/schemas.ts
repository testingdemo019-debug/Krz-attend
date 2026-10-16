/** The employment editor's checks (the prototype's validate()), in its words; the server checks everything again. */
import { z } from 'zod';
import { EMPLOYMENT_TYPES, TRACKING_REASONS } from '@/lib/vocabulary';

type EmploymentType = keyof typeof EMPLOYMENT_TYPES;
type TrackingReason = keyof typeof TRACKING_REASONS;
const TYPES = Object.keys(EMPLOYMENT_TYPES) as [EmploymentType, ...EmploymentType[]];
const REASONS = Object.keys(TRACKING_REASONS) as [TrackingReason, ...TrackingReason[]];

export interface EmploymentContext {
  /** Settings → General's Employee ID format (from /meta). */
  idPattern: string | undefined;
  locations: readonly { code: string; name: string; hasCamera: boolean }[];
  /** The face profile is ENROLLED or NEEDS_REENROL. */
  faceActive: boolean;
  accountEnabled: boolean;
}

/** The prototype's trackingProblem: why someone can't be tracked by camera, or null. */
function trackingProblem(c: EmploymentContext, locationCode: string): string | null {
  const location = c.locations.find(l => l.code === locationCode);
  if (!location) return 'No location set';
  if (!location.hasCamera) return `${location.name} has no camera`;
  if (!c.faceActive) return 'No active face profile — enrol first';
  if (!c.accountEnabled) return 'Account disabled';
  return null;
}

export function employmentSchema(c: EmploymentContext) {
  return z
    .object({
      employeeId: z.string().trim().toUpperCase(),
      department: z.string(),
      designation: z.string().trim().min(1, 'Enter a designation.'),
      type: z.enum(TYPES),
      location: z.string(),
      manager: z.string(),
      joinDate: z.string(),
      endDate: z.string(),
      mode: z.enum(['TRACKED', 'NOT_TRACKED']),
      reason: z.enum(REASONS),
    })
    .superRefine((v, ctx) => {
      if (v.employeeId && c.idPattern && !new RegExp(c.idPattern).test(v.employeeId)) {
        ctx.addIssue({
          code: 'custom',
          path: ['employeeId'],
          message: `Use the Employee ID format set in Settings → General (${c.idPattern}).`,
        });
      }
      if (v.endDate && v.joinDate && v.endDate < v.joinDate) {
        ctx.addIssue({ code: 'custom', path: ['endDate'], message: 'The end date can’t be before the join date.' });
      }
      const problem = v.mode === 'TRACKED' ? trackingProblem(c, v.location) : null;
      if (problem) ctx.addIssue({ code: 'custom', path: ['mode'], message: `Can’t track by camera: ${problem}.` });
    });
}

export type EmploymentInput = z.input<ReturnType<typeof employmentSchema>>;
export type EmploymentValues = z.output<ReturnType<typeof employmentSchema>>;

/** The API's field names (server field errors) → the form's. */
export const EMPLOYMENT_FIELDS: Record<string, keyof EmploymentInput> = {
  employeeId: 'employeeId',
  departmentCode: 'department',
  designation: 'designation',
  employmentType: 'type',
  locationCode: 'location',
  managerId: 'manager',
  joinDate: 'joinDate',
  endDate: 'endDate',
  trackingMode: 'mode',
  trackingReason: 'reason',
};
