/**
 * The enrolment wizard's state (the prototype's enrolWizard): the person, the consent being recorded, the method and
 * mode, the server session with its captures, the local previews of those captures and the outcome.
 */
import type { CaptureResult, Completed, ConsentMethod, EnrolmentMethod, EnrolmentSession } from '@/api/enrolment';
import type { PoseKey } from './guidance';

export const STEP_NAMES = ['Select person', 'Consent', 'Method', 'Capture', 'Review', 'Save'] as const;

export interface ConsentDraft {
  confirmed: boolean;
  method: ConsentMethod;
  document: File | null;
  /** Consent for the active notice is already on file and was not recorded again. */
  onFile: boolean;
}

export interface WizardState {
  step: number;
  employeeId: string | null;
  q: string;
  consent: ConsentDraft;
  /** The consent draft was filled from the person's record once. */
  consentLoaded: boolean;
  method: EnrolmentMethod;
  mode: 'REPLACE' | 'ADD';
  session: EnrolmentSession | null;
  /** Capture id → the browser's preview of it (a face crop or the uploaded photo's crop). */
  images: Record<number, string>;
  /** Upload capture id → file name; live pose → its accepted capture. */
  names: Record<number, string>;
  poses: Partial<Record<PoseKey, number>>;
  /** The blink-and-turn challenge was completed (live with MediaPipe guidance). */
  livenessPassed: boolean;
  /** MediaPipe guided the capture ("mp") or did not load ("manual"). */
  guidance: 'mp' | 'manual' | null;
  dupConfirm: boolean;
  saved: Completed | null;
}

export function initialWizard(employeeId: string | null): WizardState {
  return {
    step: employeeId ? 2 : 1,
    employeeId,
    q: '',
    consent: { confirmed: false, method: 'WRITTEN_FORM', document: null, onFile: false },
    consentLoaded: false,
    method: 'LIVE',
    mode: 'REPLACE',
    session: null,
    images: {},
    names: {},
    poses: {},
    livenessPassed: false,
    guidance: null,
    dupConfirm: false,
    saved: null,
  };
}

export type WizardAction =
  | { type: 'restart'; employeeId: string | null; step?: number }
  | { type: 'search'; q: string }
  | { type: 'go'; step: number }
  | { type: 'consent'; patch: Partial<ConsentDraft>; loaded?: boolean }
  | { type: 'method'; method: EnrolmentMethod }
  | { type: 'mode'; mode: 'REPLACE' | 'ADD' }
  | { type: 'session'; session: EnrolmentSession }
  | { type: 'captured'; result: CaptureResult; image: string; pose?: PoseKey; name?: string }
  | { type: 'removed'; captureId: number; session?: EnrolmentSession }
  | { type: 'guidance'; guidance: 'mp' | 'manual' }
  | { type: 'liveness'; passed: boolean }
  | { type: 'dupConfirm'; value: boolean }
  | { type: 'saved'; completed: Completed };

export function wizardReducer(state: WizardState, action: WizardAction): WizardState {
  switch (action.type) {
    case 'restart': {
      const next = initialWizard(action.employeeId);
      return { ...next, q: state.q, step: action.step ?? next.step };
    }
    case 'search':
      return { ...state, q: action.q };
    case 'go':
      return { ...state, step: action.step };
    case 'consent':
      return {
        ...state,
        consent: { ...state.consent, ...action.patch },
        consentLoaded: action.loaded ?? state.consentLoaded,
      };
    case 'method':
      return { ...state, method: action.method };
    case 'mode':
      return { ...state, mode: action.mode };
    case 'session': {
      // A new session (another method) starts without captures.
      const fresh = state.session?.code !== action.session.code;
      return {
        ...state,
        session: action.session,
        ...(fresh ? { images: {}, names: {}, poses: {}, livenessPassed: false, dupConfirm: false } : {}),
      };
    }
    case 'captured': {
      const { capture } = action.result;
      if (!state.session) return state;
      const poses = { ...state.poses };
      if (action.pose && capture.accepted) poses[action.pose] = capture.id;
      return {
        ...state,
        session: {
          ...state.session,
          captures: [...state.session.captures, capture],
          goodCaptures: action.result.goodCaptures,
          lockedUntil: action.result.lockedUntil ?? state.session.lockedUntil,
          livenessFailures: action.result.failedAttempts ?? state.session.livenessFailures,
        },
        images: { ...state.images, [capture.id]: action.image },
        names: action.name ? { ...state.names, [capture.id]: action.name } : state.names,
        poses,
        dupConfirm: false,
      };
    }
    case 'removed': {
      if (!state.session) return state;
      const images = Object.fromEntries(Object.entries(state.images).filter(([id]) => Number(id) !== action.captureId));
      const poses = Object.fromEntries(
        Object.entries(state.poses).filter(([, id]) => id !== action.captureId),
      ) as WizardState['poses'];
      const session = action.session ?? {
        ...state.session,
        captures: state.session.captures.filter(c => c.id !== action.captureId),
      };
      return { ...state, session, images, poses, dupConfirm: false };
    }
    case 'guidance':
      return { ...state, guidance: action.guidance };
    case 'liveness':
      return { ...state, livenessPassed: action.passed };
    case 'dupConfirm':
      return { ...state, dupConfirm: action.value };
    case 'saved':
      return { ...state, saved: action.completed };
  }
}

/** The accepted captures that become templates. */
export function goodCaptures(state: WizardState) {
  return (state.session?.captures ?? []).filter(c => c.accepted);
}
