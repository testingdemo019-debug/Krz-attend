/** Face enrolment (R15): the queue, the search, the chosen person, the session steps and test recognition. */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type QueueRow = components['schemas']['EnrolmentViews.QueueRow'];
export type EnrolmentQueue = components['schemas']['EnrolmentViews.Queue'];
export type Candidate = components['schemas']['EnrolmentViews.Candidate'];
export type EnrolmentRules = components['schemas']['EnrolmentSettings'];
export type ConsentNotice = components['schemas']['ConsentNoticeService.NoticeView'];
export type EnrolmentSession = components['schemas']['EnrolmentViews.Session'];
export type Capture = components['schemas']['EnrolmentViews.Capture'];
export type CaptureResult = components['schemas']['EnrolmentViews.CaptureResult'];
export type DuplicateCheck = components['schemas']['EnrolmentViews.DuplicateCheck'];
export type Completed = components['schemas']['EnrolmentViews.Completed'];
export type TestResult = components['schemas']['EnrolmentViews.TestResult'];
export type CaptureCamera = components['schemas']['EnrolmentViews.CaptureCamera'];
export type CaptureStatus = components['schemas']['EnrolmentViews.CaptureStatus'];
/** LIVE (guided webcam), UPLOAD (photos) or CAMERA (capture at an entry point: the attendance camera, D-118). */
export type EnrolmentMethod = 'LIVE' | 'UPLOAD' | 'CAMERA';
export type ConsentMethod = 'WRITTEN_FORM' | 'EMAIL_CONFIRMATION';

const sessionPath = (code: string, rest = '') => `/enrolment/sessions/${encodeURIComponent(code)}${rest}`;

export function useEnrolmentQueue() {
  return useQuery({
    queryKey: [...queryKeys.enrolment, 'queue'],
    queryFn: ({ signal }) => request<EnrolmentQueue>('/enrolment/queue', { signal }),
  });
}

/** Anyone by name or Employee ID (at most 12), while the search box has text. */
export function useEnrolmentSearch(q: string) {
  const text = q.trim();
  return useQuery({
    queryKey: [...queryKeys.enrolment, 'search', text],
    queryFn: ({ signal }) => request<QueueRow[]>('/enrolment/people', { signal, query: { q: text } }),
    enabled: text !== '',
    placeholderData: keepPreviousData,
  });
}

export function useEnrolmentCandidate(employeeId: string | null) {
  return useQuery({
    queryKey: [...queryKeys.enrolment, 'candidate', employeeId],
    queryFn: ({ signal }) =>
      request<Candidate>(`/enrolment/people/${encodeURIComponent(employeeId ?? '')}`, { signal }),
    enabled: employeeId !== null,
  });
}

export function useEnrolmentRules() {
  return useQuery({
    queryKey: [...queryKeys.enrolment, 'rules'],
    queryFn: ({ signal }) => request<EnrolmentRules>('/enrolment/rules', { signal }),
  });
}

export function useActiveNotice(enabled: boolean) {
  return useQuery({
    queryKey: [...queryKeys.enrolment, 'notice'],
    queryFn: ({ signal }) => request<ConsentNotice>('/consent-notices/active', { signal }),
    enabled,
  });
}

/** Starts a session for the person (an open one of theirs ends; refused while enrolment is locked). */
export function useStartSession() {
  return useMutation({
    mutationFn: (body: { employeeId: string; method: EnrolmentMethod }) =>
      request<EnrolmentSession>('/enrolment/sessions', { method: 'POST', body }),
  });
}

export type ConsentDecision =
  | { decision: 'GIVEN'; method: ConsentMethod; noticeVersion: number; document: File | null }
  | { decision: 'DECLINED'; noticeVersion: number };

export function useRecordConsent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ code, consent }: { code: string; consent: ConsentDecision }) => {
      const form = new FormData();
      form.set('decision', consent.decision);
      form.set('noticeVersion', String(consent.noticeVersion));
      if (consent.decision === 'GIVEN') {
        form.set('method', consent.method);
        if (consent.document) form.set('document', consent.document);
      }
      return request<EnrolmentSession>(sessionPath(code, '/consent'), { method: 'POST', body: form });
    },
    onSettled: () =>
      Promise.all(
        [queryKeys.enrolment, queryKeys.employees].map(key => queryClient.invalidateQueries({ queryKey: key })),
      ),
  });
}

/** One capture or photo, checked by the worker: quality, liveness, accepted or why not. */
export function useCapture() {
  return useMutation({
    mutationFn: ({ code, image, pose }: { code: string; image: Blob; pose: string | null }) => {
      const form = new FormData();
      form.set('image', image, 'capture.jpg');
      if (pose) form.set('pose', pose);
      return request<CaptureResult>(sessionPath(code, '/captures'), { method: 'POST', body: form });
    },
  });
}

export function useRemoveCapture() {
  return useMutation({
    mutationFn: ({ code, captureId }: { code: string; captureId: number }) =>
      request<EnrolmentSession>(sessionPath(code, `/captures/${String(captureId)}`), { method: 'DELETE' }),
  });
}

/** Other enrolled employees resembling the chosen captures (asked again whenever they change). */
export function useDuplicateCheck(code: string | null, captureIds: readonly number[]) {
  return useQuery({
    queryKey: [...queryKeys.enrolment, 'duplicates', code, captureIds],
    queryFn: ({ signal }) =>
      request<DuplicateCheck>(sessionPath(code ?? '', '/duplicate-check'), { method: 'POST', signal }),
    enabled: code !== null && captureIds.length > 0,
    staleTime: Infinity,
  });
}

export function useCompleteEnrolment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      code,
      ...body
    }: {
      code: string;
      captureIds: number[];
      lookalikeConfirmed: boolean;
      mode: 'REPLACE' | 'ADD';
    }) => request<Completed>(sessionPath(code, '/complete'), { method: 'POST', body }),
    onSettled: () =>
      Promise.all(
        [queryKeys.enrolment, queryKeys.employees, queryKeys.attendance, queryKeys.dashboard].map(key =>
          queryClient.invalidateQueries({ queryKey: key }),
        ),
      ),
  });
}

/** The enabled cameras a capture at an entry point can use. */
export function useCaptureCameras(enabled: boolean) {
  return useQuery({
    queryKey: [...queryKeys.enrolment, 'cameras'],
    queryFn: ({ signal }) => request<CaptureCamera[]>('/enrolment/cameras', { signal }),
    enabled,
  });
}

/** The camera's latest picture (never cached); {@code tick} makes each refresh a new image request. */
export function cameraSnapshotUrl(cameraCode: string, tick: number): string {
  return `/api/v1/enrolment/cameras/${encodeURIComponent(cameraCode)}/snapshot?t=${String(tick)}`;
}

/** A capture's image as the server stored it (the face crop of a capture at an entry point). */
export function captureImageUrl(code: string, captureId: number): string {
  return `/api/v1${sessionPath(code, `/captures/${String(captureId)}/image`)}`;
}

export function useStartCapture() {
  return useMutation({
    mutationFn: ({ code, cameraCode }: { code: string; cameraCode: string }) =>
      request<CaptureStatus>(sessionPath(code, '/capture'), { method: 'POST', body: { cameraCode } }),
  });
}

/** What the running capture holds, asked every second while it runs. */
export function useCaptureStatus(code: string, running: boolean) {
  return useQuery({
    queryKey: [...queryKeys.enrolment, 'capture', code],
    queryFn: ({ signal }) => request<CaptureStatus>(sessionPath(code, '/capture'), { signal }),
    enabled: running,
    refetchInterval: running ? 1000 : false,
  });
}

/** Stops the capture: its samples become the session's captures. */
export function useStopCapture() {
  return useMutation({
    mutationFn: (code: string) => request<EnrolmentSession>(sessionPath(code, '/capture/stop'), { method: 'POST' }),
  });
}

/** Ends an open session; its captures are discarded. */
export function cancelSession(code: string): Promise<void> {
  return request<undefined>(sessionPath(code, '/cancel'), { method: 'POST' });
}

/** A System Administrator lifts a liveness lockout early. */
export function useUnlockEnrolment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (employeeId: string) =>
      request<undefined>(`/enrolment/lockouts/${encodeURIComponent(employeeId)}/unlock`, { method: 'POST' }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.enrolment }),
  });
}

export function testRecognition(employeeId: string, image: Blob): Promise<TestResult> {
  const form = new FormData();
  form.set('employeeId', employeeId);
  form.set('image', image, 'frame.jpg');
  return request<TestResult>('/enrolment/test', { method: 'POST', body: form });
}
