/**
 * One vocabulary (F3, F6.9): every enum value the API sends → the prototype's label, chip tone, icon and help text.
 * No screen spells a label itself. Keys are the backend's values; labels are the prototype's (Constants section).
 */
import type { IconName } from '@/components/icons/Icon';
import type { ChipTone } from '@/components/ui/Chip';

export interface Term {
  label: string;
  tone: ChipTone;
  icon: IconName;
  description?: string;
}

/** A face profile's status (FACE_STATUS). */
export const FACE_STATUS = {
  ENROLLED: {
    label: 'Enrolled',
    tone: 'green',
    icon: 'checkCircle',
    description: 'Has face templates: recognised by the camera',
  },
  NOT_ENROLLED: { label: 'Not enrolled', tone: 'grey', icon: 'minus', description: 'No face templates yet' },
  NEEDS_REENROL: {
    label: 'Needs re-enrolment',
    tone: 'amber',
    icon: 'refresh',
    description: 'The templates need replacing (e.g. after a model change): enrol again',
  },
  CONSENT_PENDING: {
    label: 'Consent pending',
    tone: 'amber',
    icon: 'hourglass',
    description: 'Waiting for consent before enrolment',
  },
  DEACTIVATED: { label: 'Deactivated', tone: 'grey', icon: 'ban', description: 'The face profile is switched off' },
  ERASED: { label: 'Erased', tone: 'red', icon: 'trash', description: 'Face data erased on request' },
} as const satisfies Record<string, Term>;
export type FaceStatus = keyof typeof FACE_STATUS;

/** How a crossing was identified (METHODS). */
export const METHODS = {
  FACE: { label: 'Face', icon: 'face', description: 'Recognised by face' },
  REID: {
    label: 'Re-ID',
    icon: 'body',
    description: 'Recognised from behind by body and clothes, matched with who came in today',
  },
  REVIEW: { label: 'Review', icon: 'review', description: 'Decided by a reviewer in the Review Queue' },
} as const satisfies Record<string, { label: string; icon: IconName; description: string }>;
export type Method = keyof typeof METHODS;

/** What a crossing did to attendance (PUNCH_EFFECTS + EFFECT_CHIPS). */
export const EFFECTS = {
  CHECK_IN: { label: 'Check-in', tone: 'green', description: 'The first IN of the shift day: the check-in time' },
  CHECK_OUT: {
    label: 'Check-out',
    tone: 'blue',
    description: 'The last OUT of the shift day so far: the check-out time',
  },
  MOVEMENT: {
    label: 'Movement',
    tone: 'grey',
    description: 'Recorded, but neither the check-in nor the check-out (e.g. a step out in between)',
  },
  IGNORED_DUPLICATE: {
    label: 'Ignored – duplicate',
    tone: 'amber',
    description: 'The same direction again within the duplicate window: not counted',
  },
  IGNORED_TURNED_BACK: {
    label: 'Ignored – turned back',
    tone: 'amber',
    description: 'An IN and an OUT within the turn-back window cancel each other',
  },
  IGNORED_UNKNOWN: {
    label: 'Ignored – unknown',
    tone: 'grey',
    description: 'Nobody was identified and the review item expired: not counted',
  },
  PENDING_REVIEW: { label: 'Pending review', tone: 'purple', description: 'Waiting for a reviewer to say who it was' },
  VOIDED: { label: 'Voided', tone: 'red', description: 'Removed by a correction (wrong person)' },
} as const satisfies Record<string, { label: string; tone: ChipTone; description: string }>;
export type Effect = keyof typeof EFFECTS;

/** How a crossing was decided (DECISIONS). */
export const DECISIONS = {
  AUTO: 'Auto',
  PENDING_REVIEW: 'Pending review',
  RESOLVED_IN_REVIEW: 'Resolved in review',
  NOT_AN_EMPLOYEE: 'Not an employee',
  REJECTED: 'Rejected',
  SPOOF_CONFIRMED: 'Spoof confirmed',
  EXPIRED_NO_ACTION: 'Expired – no action',
} as const satisfies Record<string, string>;
export type Decision = keyof typeof DECISIONS;

/** Review item types (REVIEW_TYPES): tab label, singular and icon. */
export const REVIEW_TYPES = {
  UNKNOWN: { label: 'Unknown faces', short: 'Unknown face', icon: 'user' },
  UNCERTAIN: { label: 'Uncertain matches', short: 'Uncertain match', icon: 'help' },
  REID: { label: 'Back-view exits', short: 'Back-view exit', icon: 'body' },
  LIVENESS: { label: 'Liveness alerts', short: 'Liveness alert', icon: 'shieldAlert' },
} as const satisfies Record<string, { label: string; short: string; icon: IconName }>;
export type ReviewType = keyof typeof REVIEW_TYPES;

export const REVIEW_STATUS = { OPEN: 'open', RESOLVED: 'resolved', EXPIRED: 'expired' } as const;

/** Review decisions (REVIEW_ACTION_LABELS): the label of the toast, the audit entry and the Resolved tab. */
export const REVIEW_ACTIONS = {
  CONFIRM: 'Confirmed',
  ASSIGN: 'Assigned to employee',
  NOT_EMPLOYEE: 'Not an employee',
  REJECT: 'Rejected — bad image',
  SPOOF: 'Spoof confirmed',
  FALSE_ALARM: 'False alarm — confirmed',
} as const;
export type ReviewAction = keyof typeof REVIEW_ACTIONS;

/** How a closed item ended (the Resolved tab): a decision, or expiry when nobody resolved an unknown face in time. */
export const REVIEW_OUTCOMES = { ...REVIEW_ACTIONS, EXPIRED: 'Expired – no action' } as const;

/** A camera's direction mode (DIRECTION_MODES). */
export const DIRECTION_MODES = {
  BOTH: { label: 'IN and OUT by crossing direction', short: 'IN + OUT' },
  IN: { label: 'IN only', short: 'IN only' },
  OUT: { label: 'OUT only', short: 'OUT only' },
  MONITOR: { label: 'Monitor only (no punches)', short: 'Monitor' },
} as const satisfies Record<string, { label: string; short: string }>;
export type DirectionMode = keyof typeof DIRECTION_MODES;

/** How a camera counts crossings (D-120): each method has its own drawing on the picture and its own settings. */
export const COUNTING_METHODS = {
  LINE_CROSSING: {
    label: 'Line crossing',
    help: 'People are counted when their feet cross a line on the floor. Best when the floor in front of the door is in view.',
  },
  DOORWAY_ZONE: {
    label: 'Doorway zone',
    help: 'For compact entrances: people are counted by where they appear and disappear — through the door or into the room. No floor line needed.',
  },
} as const satisfies Record<string, { label: string; help: string }>;
export type CountingMethod = keyof typeof COUNTING_METHODS;

/** Why someone is not tracked by camera (TRACKING_REASONS). */
export const TRACKING_REASONS = {
  NO_FACE: 'No face profile',
  NO_CAMERA: 'Location without a camera',
  EXEMPT: 'Exempt',
} as const satisfies Record<string, string>;

export const EMPLOYMENT_TYPES = { EMPLOYEE: 'Employee', CONTRACTOR: 'Contractor', INTERN: 'Intern' } as const;

/** A punch's state in the HRMS outbox (HRMS_CHIPS); "held" is the dummy integration's state (P-07). */
export const HRMS_STATUS = {
  SENT: { label: 'Sent', tone: 'green', icon: 'check', description: 'Delivered to KRZ HRMS' },
  PENDING: { label: 'Pending', tone: 'grey', icon: 'clock', description: 'Waiting to be sent' },
  FAILED: { label: 'Failed', tone: 'red', icon: 'alertCircle', description: 'Sending failed; it is tried again' },
  VOIDED: { label: 'Voided', tone: 'grey', icon: 'ban', description: 'Cancelled by a correction' },
  BLOCKED_NO_EMPLOYEE_ID: {
    label: 'Blocked – no Employee ID',
    tone: 'amber',
    icon: 'alert',
    description: 'The person has no HRMS Employee ID yet',
  },
  HELD_NOT_CONNECTED: {
    label: 'Held – HRMS not connected',
    tone: 'grey',
    icon: 'hourglass',
    description: 'Kept until KRZ HRMS is connected',
  },
} as const satisfies Record<string, Term>;
export type HrmsStatus = keyof typeof HRMS_STATUS;

/** Liveness of a crossing (livenessHTML); "skipped" when the camera's liveness check is off. */
export const LIVENESS = {
  PASSED: {
    label: 'Liveness passed',
    tone: 'green',
    icon: 'shieldCheck',
    description: 'The face looked real, not a photo or a screen',
  },
  FAILED: {
    label: 'Liveness failed',
    tone: 'red',
    icon: 'shieldAlert',
    description: 'Looked like a photo or a screen: sent to Review',
  },
  SKIPPED: {
    label: 'Liveness not checked',
    tone: 'grey',
    icon: 'shield',
    description: 'The liveness check is off for this camera',
  },
} as const satisfies Record<string, Term>;

/** Confidence bands (confidenceBand): the chip shown instead of a score without settings.recognition. */
export const CONFIDENCE = {
  HIGH: { label: 'High', tone: 'green', description: 'Match score well above the accept threshold' },
  MEDIUM: { label: 'Medium', tone: 'blue', description: 'Match score above the accept threshold' },
  LOW: { label: 'Low', tone: 'amber', description: 'Match score below the accept threshold' },
} as const satisfies Record<string, { label: string; tone: ChipTone; description: string }>;
export type Confidence = keyof typeof CONFIDENCE;

/** Consent to face recognition (CONSENT_STATUS). */
export const CONSENT_STATUS = {
  GIVEN: { label: 'Given', tone: 'green', icon: 'check', description: 'Consent to face recognition is on file' },
  PENDING: { label: 'Pending', tone: 'amber', icon: 'hourglass', description: 'Asked, not answered yet' },
  NONE: { label: 'Not asked', tone: 'grey', icon: 'minus', description: 'Consent has not been asked yet' },
  WITHDRAWN: { label: 'Withdrawn', tone: 'red', icon: 'x', description: 'Consent withdrawn: face data is removed' },
} as const satisfies Record<string, Term>;

/** How consent was recorded or withdrawn. */
export const CONSENT_METHODS = {
  WRITTEN_FORM: 'Written (signed form)',
  EMAIL_CONFIRMATION: 'E-mail confirmation',
  WRITTEN_REQUEST: 'Written request',
} as const;

/** Organisation master data: ARCHIVED is shown as "Inactive", as in the prototype. */
export const RECORD_STATUS = {
  ACTIVE: { label: 'Active', tone: 'green' },
  ARCHIVED: { label: 'Inactive', tone: 'grey' },
} as const;

/** Requests from employees about their data (Settings → Privacy & retention). */
export const PRIVACY_REQUEST_TYPES = {
  ACCESS: 'Access request',
  CORRECTION: 'Correction',
  ERASURE: 'Erasure',
} as const;

export const PRIVACY_REQUEST_STATUS = {
  OPEN: { label: 'Open', tone: 'amber' },
  IN_PROGRESS: { label: 'In progress', tone: 'blue' },
  COMPLETED: { label: 'Completed', tone: 'green' },
  DECLINED: { label: 'Declined', tone: 'grey' },
} as const;

/** Audit log categories (the backend stores them UPPER_SNAKE). */
export const AUDIT_CATEGORIES = {
  SIGN_IN: 'Sign-in',
  USERS_ROLES: 'Users & roles',
  ORGANISATION: 'Organisation',
  EMPLOYEES: 'Employees',
  ENROLMENT: 'Enrolment',
  REVIEW: 'Review',
  ATTENDANCE: 'Attendance',
  CAMERAS: 'Cameras',
  RECOGNITION: 'Recognition',
  SETTINGS: 'Settings',
  HRMS: 'HRMS',
  PRIVACY: 'Privacy',
  EXPORT: 'Export',
  LIVE_VIEW: 'Live View',
  SYSTEM: 'System',
} as const;

/** A shift allocation's state on today's date. */
export const ALLOCATION_STATE = {
  ACTIVE: { label: 'Active', tone: 'green' },
  SCHEDULED: { label: 'Scheduled', tone: 'blue' },
  ENDED: { label: 'Ended', tone: 'grey' },
  CANCELLED: { label: 'Cancelled', tone: 'grey' },
} as const;

/** The shift colour palette (the server accepts only these). */
export const SHIFT_COLOURS = ['#2563eb', '#0d9488', '#b45309', '#7c3aed', '#be185d', '#15803d', '#4b5563'] as const;

export const HOLIDAY_TYPES = {
  PUBLIC: { label: 'Public', tone: 'blue' },
  OPTIONAL: { label: 'Optional', tone: 'grey' },
} as const;

/** A face template's pose (uploaded photos have none: "Upload") and where it came from. */
export const TEMPLATE_POSES = {
  FRONT: 'Front',
  TURN_LEFT: 'Turn left',
  TURN_RIGHT: 'Turn right',
  CHIN_DOWN: 'Chin down',
} as const;
export const TEMPLATE_SOURCES = {
  LIVE: 'live',
  UPLOAD: 'upload',
  REVIEW: 'from review',
  CAMERA: 'entry camera',
  CROSSING: 'from a crossing',
} as const;
/** The light a template from the attendance camera was taken in (D-118). */
export const TEMPLATE_LIGHTS = { DAY: 'Day', EVENING: 'Evening', NIGHT_IR: 'Night (infrared)' } as const;
/** Templates taken by the attendance camera itself: they show its angle and light. */
export const CAMERA_TEMPLATE_SOURCES: readonly string[] = ['CAMERA', 'REVIEW', 'CROSSING'];
/** Face-profile history actions (the profile's Activity tab). */
export const PROFILE_HISTORY_ACTIONS = {
  ENROLLED: 'Enrolled',
  REENROLLED: 'Re-enrolled',
  FLAGGED_REENROL: 'Flagged for re-enrolment',
  DEACTIVATED: 'Face profile deactivated',
  REACTIVATED: 'Face profile reactivated',
  ERASED: 'Biometric data erased',
  TEMPLATE_ADDED_FROM_REVIEW: 'Template added from review',
  TEMPLATE_ADDED_FROM_CROSSING: 'Template added from a crossing',
  ENROLMENT_DECLINED: 'Enrolment declined',
  CONSENT_WITHDRAWN: 'Consent withdrawn',
} as const;

/** Why a face profile is deactivated or erased (the server accepts these; "Other" needs a note). */
export const DEACTIVATE_REASONS = [
  'Long leave',
  'Appearance changed — re-enrolment planned',
  'Requested by the employee',
  'Other',
] as const;
export const ERASE_REASONS = ['Consent withdrawn', 'Left the organisation', 'Privacy request', 'Other'] as const;

/** A shift day's attendance status (ATTENDANCE_STATUS): label, chip, icon, the log's code and the help text. */
export const ATTENDANCE_STATUS = {
  INSIDE: {
    label: 'Inside',
    tone: 'green',
    icon: 'moveIn',
    code: 'P',
    description: 'Checked in and currently inside.',
  },
  STEPPED_OUT: {
    label: 'Stepped out',
    tone: 'blue',
    icon: 'moveOut',
    code: 'P',
    description: 'Left before shift end; check-out stays open until they return or the day closes.',
  },
  CHECKED_OUT: {
    label: 'Checked out',
    tone: 'green',
    icon: 'checkCircle',
    code: 'P',
    description: 'Checked in and out.',
  },
  LATE: {
    label: 'Late',
    tone: 'amber',
    icon: 'clock',
    code: 'L',
    description: 'Check-in after shift start + late grace.',
  },
  EARLY_EXIT: {
    label: 'Early exit',
    tone: 'amber',
    icon: 'logout',
    code: 'EE',
    description: 'Final check-out before shift end − early-exit grace.',
  },
  HALF_DAY: {
    label: 'Half day',
    tone: 'amber',
    icon: 'clock',
    code: 'HD',
    description: 'Worked hours below the half-day limit.',
  },
  MISSING_CHECK_OUT: {
    label: 'Missing check-out',
    tone: 'purple',
    icon: 'alertCircle',
    code: 'MO',
    description: 'Inside at day close with no OUT. HRMS regularises it.',
  },
  MISSING_CHECK_IN: {
    label: 'Missing check-in',
    tone: 'purple',
    icon: 'alertCircle',
    code: 'MI',
    description: 'An OUT with no IN that shift day. HRMS regularises it.',
  },
  NOT_YET_IN: {
    label: 'Not yet in',
    tone: 'grey',
    icon: 'hourglass',
    code: '·',
    description: 'No check-in yet; becomes Absent after the cut-off.',
  },
  ABSENT: {
    label: 'Absent',
    tone: 'red',
    icon: 'x',
    code: 'A',
    description: 'No check-in by the cut-off, or worked below the absent limit.',
  },
  HOLIDAY: {
    label: 'Holiday',
    tone: 'blue',
    icon: 'sun',
    code: 'H',
    description: 'Public holiday for this employee’s location.',
  },
  WEEKLY_OFF: {
    label: 'Weekly off',
    tone: 'grey',
    icon: 'minus',
    code: 'WO',
    description: 'Weekly off in the employee’s shift.',
  },
  WORKED_ON_HOLIDAY: {
    label: 'Worked on holiday',
    tone: 'blue',
    icon: 'sun',
    code: 'WH',
    description: 'Present on a holiday.',
  },
  WORKED_ON_WEEKLY_OFF: {
    label: 'Worked on weekly off',
    tone: 'blue',
    icon: 'calendar',
    code: 'WW',
    description: 'Present on a weekly off.',
  },
  NOT_TRACKED: {
    label: 'Not tracked',
    tone: 'grey',
    icon: 'eyeOff',
    code: 'NT',
    description: 'Attendance for this employee is not recorded by camera.',
  },
} as const satisfies Record<string, Term & { code: string }>;
export type AttendanceStatus = keyof typeof ATTENDANCE_STATUS;

/** Status levels of the system status rows and the pill: CSS class of .sdot / .status-pill. */
export const STATUS_LEVEL = { OK: 'ok', WARN: 'warn', BAD: 'bad', DISABLED: 'off' } as const;

/** The icon of each notification rule (the prototype's notification popover). */
export const NOTIFICATION_ICONS: Record<string, IconName> = {
  'NR-01': 'wifiOff',
  'NR-02': 'shieldAlert',
  'NR-03': 'review',
  'NR-04': 'alertCircle',
  'NR-05': 'user',
  'NR-06': 'userPlus',
  'NR-07': 'alert',
  'NR-08': 'calendarCheck',
};

/** Looks up a term, tolerating values this build does not know yet (shown as-is, grey). */
export function term<T extends Record<string, unknown>>(
  map: T,
  value: string | null | undefined,
): T[keyof T] | undefined {
  return value !== null && value !== undefined && value in map ? map[value as keyof T] : undefined;
}
