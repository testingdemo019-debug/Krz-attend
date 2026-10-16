/**
 * Settings: General, Users & roles, Microsoft sign-in, Notifications, Privacy & retention, Audit log and System. Each
 * save answers with the saved values, which replace the cached ones (the page's draft then starts from them).
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { request, type QueryValue } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

type S = components['schemas'];
export type GeneralView = S['GeneralSettingsService.GeneralView'];
export type UpdateGeneral = S['SettingsController.UpdateGeneralRequest'];
export type UserView = S['AccessViews.UserView'];
export type UserFacets = S['AccessViews.UserFacets'];
export type RoleView = S['AccessViews.RoleView'];
export type AccessRequestView = S['AccessViews.AccessRequestView'];
export type RoleChange = S['AccessController.CreateRoleRequest'];
export type SignInView = S['SignInSettingsService.SignInView'];
export type SignInTest = S['SignInSettingsService.TestResult'];
export type SyncRun = S['DirectorySyncService.SyncRunView'];
export type NotificationSettings = S['NotificationSettingsService.NotificationSettingsView'];
export type NotificationRule = S['NotificationSettingsService.RuleView'];
export type RuleChange = S['NotificationController.UpdateRuleRequest'];
export type PrivacyView = S['PrivacyController.PrivacyView'];
export type NoticesView = S['ConsentNoticeService.NoticesView'];
export type PrivacyRequest = S['PrivacyRequestService.PrivacyRequestView'];
export type PrivacyRequestChange = S['PrivacyController.UpdatePrivacyRequestBody'];
export type AuditEntry = S['AuditLogService.AuditEntry'];
export type AuditFacets = S['AuditLogService.Facets'];
export type StatusRow = S['SystemStatusService.StatusRow'];
export type DayCloseRun = S['DayCloseService.RunView'];
export type About = S['SystemController.About'];

const settings = (area: string): QueryKey => [...queryKeys.settings, area];

/** A save whose answer is the new state of `key`. */
function useSave<V, R>(key: QueryKey, save: (value: V) => Promise<R>, also: readonly QueryKey[] = []) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: save,
    onSuccess: saved => {
      client.setQueryData(key, saved);
      for (const k of also) void client.invalidateQueries({ queryKey: k });
    },
  });
}

/** A change after which `keys` are asked again. */
function useChange<V, R>(change: (value: V) => Promise<R>, keys: readonly QueryKey[]) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: change,
    onSettled: () => Promise.all(keys.map(queryKey => client.invalidateQueries({ queryKey }))),
  });
}

// General

export function useGeneral() {
  return useQuery({
    queryKey: settings('general'),
    queryFn: ({ signal }) => request<GeneralView>('/settings/general', { signal }),
  });
}

/** The organisation name and formats show in the shell and on the sign-in screen too. */
export function useSaveGeneral() {
  return useSave(
    settings('general'),
    (body: UpdateGeneral) => request<GeneralView>('/settings/general', { method: 'PUT', body }),
    [queryKeys.meta, queryKeys.branding],
  );
}

export function useLogo() {
  return useChange(
    (file: File | null) => {
      if (!file) return request<undefined>('/settings/general/logo', { method: 'DELETE' });
      const body = new FormData();
      body.append('file', file);
      return request<unknown>('/settings/general/logo', { method: 'POST', body });
    },
    [settings('general'), queryKeys.meta, queryKeys.branding],
  );
}

// Users & roles

const ACCESS: readonly QueryKey[] = [settings('users'), settings('roles'), queryKeys.accessRequests];

export function useUsers(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...settings('users'), 'list', query],
    queryFn: ({ signal }) => request<S['PageResponseUserView']>('/settings/users', { signal, query }),
    placeholderData: keepPreviousData,
  });
}

/** Everyone who has access, page by page ("Give access" leaves them out of the picker). */
export function useAccessIds(enabled: boolean) {
  return useQuery({
    queryKey: [...settings('users'), 'ids'],
    queryFn: async ({ signal }) => {
      const ids = new Set<string>();
      for (let page = 0; ; page += 1) {
        const batch = await request<S['PageResponseUserView']>('/settings/users', {
          signal,
          query: { size: 200, page },
        });
        for (const u of batch.items) ids.add(u.employeeId);
        if (ids.size >= batch.total || batch.items.length === 0) return ids;
      }
    },
    enabled,
  });
}

export function useUserFacets() {
  return useQuery({
    queryKey: [...settings('users'), 'facets'],
    queryFn: ({ signal }) => request<UserFacets>('/settings/users/facets', { signal }),
  });
}

/** Give access (no version) or change someone's roles. */
export function useSetRoles() {
  return useChange(
    ({ employeeId, roleIds, version }: { employeeId: string; roleIds: string[]; version: number | null }) =>
      version === null
        ? request<undefined>('/settings/users', { method: 'POST', body: { employeeId, roleIds } })
        : request<undefined>(`/settings/users/${employeeId}`, { method: 'PUT', body: { roleIds, version } }),
    ACCESS,
  );
}

export function useRemoveAccess() {
  return useChange(
    (employeeId: string) => request<undefined>(`/settings/users/${employeeId}`, { method: 'DELETE' }),
    ACCESS,
  );
}

export function useRoles(enabled = true) {
  return useQuery({
    queryKey: settings('roles'),
    queryFn: ({ signal }) => request<RoleView[]>('/settings/roles', { signal }),
    enabled,
  });
}

/** Create a role (no code) or save one. */
export function useSaveRole() {
  return useChange(
    ({ code, version, ...body }: RoleChange & { code: string | null; version: number }) =>
      code === null
        ? request<RoleView>('/settings/roles', { method: 'POST', body })
        : request<RoleView>(`/settings/roles/${code}`, { method: 'PUT', body: { ...body, version } }),
    ACCESS,
  );
}

export function useDeleteRole() {
  return useChange(
    (code: string) => request<undefined>(`/settings/roles/${code}`, { method: 'DELETE' }),
    [...ACCESS, settings('notifications')],
  );
}

export function useAccessRequests(enabled: boolean) {
  return useQuery({
    queryKey: [...queryKeys.accessRequests, 'all'],
    queryFn: ({ signal }) => request<AccessRequestView[]>('/settings/access-requests', { signal }),
    enabled,
  });
}

export function useDecideRequest() {
  return useChange(
    (decision: { code: string; roleIds: string[] } | { code: string; reason: string }) =>
      'roleIds' in decision
        ? request<undefined>(`/settings/access-requests/${decision.code}/approve`, {
            method: 'POST',
            body: { roleIds: decision.roleIds },
          })
        : request<undefined>(`/settings/access-requests/${decision.code}/decline`, {
            method: 'POST',
            body: { reason: decision.reason },
          }),
    ACCESS,
  );
}

// Microsoft sign-in

export function useSignIn() {
  return useQuery({
    queryKey: settings('signin'),
    queryFn: ({ signal }) => request<SignInView>('/settings/signin', { signal }),
  });
}

export function useSaveSignIn() {
  return useSave(
    settings('signin'),
    (body: S['DirectoryController.UpdateSignInRequest']) =>
      request<SignInView>('/settings/signin', { method: 'PUT', body }),
    [queryKeys.meta],
  );
}

export function useTestSignIn() {
  return useChange(() => request<SignInTest>('/settings/signin/test', { method: 'POST' }), [settings('signin')]);
}

export type Unsynced = S['DirectorySyncService.Unsynced'];

/** People in the tenant outside the sync-scope groups (Users & roles names them). */
export function useUnsynced() {
  return useQuery({
    queryKey: [...settings('users'), 'unsynced'],
    queryFn: ({ signal }) => request<Unsynced>('/directory/unsynced', { signal }),
  });
}

/** The prototype's sync history shows the last 8 runs. */
export function useSyncRuns() {
  return useQuery({
    queryKey: [...settings('signin'), 'runs'],
    queryFn: ({ signal }) => request<SyncRun[]>('/directory/sync-runs', { signal }).then(runs => runs.slice(0, 8)),
  });
}

// Notifications

export function useNotificationSettings() {
  return useQuery({
    queryKey: settings('notifications'),
    queryFn: ({ signal }) => request<NotificationSettings>('/settings/notifications', { signal }),
  });
}

/** Saves the changed rules one after the other (each has its own version), then reads the page again. */
export function useSaveNotificationRules() {
  return useChange(
    async (changes: readonly { code: string; change: RuleChange }[]) => {
      for (const { code, change } of changes) {
        await request<NotificationRule>(`/settings/notifications/${code}`, { method: 'PUT', body: change });
      }
    },
    [settings('notifications')],
  );
}

export function useTestEmail() {
  return useMutation({
    mutationFn: (to: string) =>
      request<undefined>('/settings/notifications/test-email', { method: 'POST', body: { to } }),
  });
}

// Privacy & retention

export function usePrivacy() {
  return useQuery({
    queryKey: settings('privacy'),
    queryFn: ({ signal }) => request<PrivacyView>('/settings/privacy', { signal }),
  });
}

export function useSavePrivacy() {
  return useSave(
    settings('privacy'),
    (body: S['PrivacyController.UpdatePrivacyRequest']) =>
      request<PrivacyView>('/settings/privacy', { method: 'PUT', body }),
    [queryKeys.meta],
  );
}

export function useNotices() {
  return useQuery({
    queryKey: [...settings('privacy'), 'notices'],
    queryFn: ({ signal }) => request<NoticesView>('/settings/privacy/notices', { signal }),
  });
}

export function usePublishNotice() {
  return useChange(
    (text: string) => request<unknown>('/settings/privacy/notices', { method: 'POST', body: { text } }),
    [[...settings('privacy'), 'notices'], queryKeys.enrolment],
  );
}

export function usePrivacyRequests() {
  return useQuery({
    queryKey: [...settings('privacy'), 'requests'],
    queryFn: ({ signal }) => request<PrivacyRequest[]>('/settings/privacy/requests', { signal }),
  });
}

export function useRecordPrivacyRequest() {
  return useChange(
    (body: S['PrivacyController.CreatePrivacyRequest']) =>
      request<PrivacyRequest>('/settings/privacy/requests', { method: 'POST', body }),
    [[...settings('privacy'), 'requests']],
  );
}

export function useMovePrivacyRequest() {
  return useChange(
    ({ code, ...body }: PrivacyRequestChange & { code: string }) =>
      request<PrivacyRequest>(`/settings/privacy/requests/${code}`, { method: 'PUT', body }),
    [[...settings('privacy'), 'requests']],
  );
}

// Audit log

export function useAudit(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...queryKeys.audit, 'list', query],
    queryFn: ({ signal }) => request<S['PageResponseAuditEntry']>('/audit', { signal, query }),
    placeholderData: keepPreviousData,
    staleTime: 0,
  });
}

export function useAuditFacets(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...queryKeys.audit, 'facets', query],
    queryFn: ({ signal }) => request<AuditFacets>('/audit/facets', { signal, query }),
    placeholderData: keepPreviousData,
  });
}

// System

export function useSystemStatus() {
  return useQuery({
    queryKey: [...queryKeys.system, 'status'],
    queryFn: ({ signal }) => request<StatusRow[]>('/system/status', { signal }),
    staleTime: 5_000,
    refetchInterval: 15_000,
  });
}

export function useDayCloseRuns() {
  return useQuery({
    queryKey: [...queryKeys.system, 'day-close-runs'],
    queryFn: ({ signal }) =>
      request<S['PageResponseRunView']>('/system/day-close-runs', { signal, query: { size: 12 } }).then(
        page => page.items,
      ),
  });
}

export function useRunDayClose() {
  return useChange(
    () => request<DayCloseRun[]>('/system/day-close/run', { method: 'POST' }),
    [queryKeys.system, queryKeys.attendance],
  );
}

export function useAbout() {
  return useQuery({
    queryKey: [...queryKeys.system, 'about'],
    queryFn: ({ signal }) => request<About>('/system/about', { signal }),
  });
}

// Recognition, Enrolment & quality

export type RecognitionView = S['RecognitionViews.RecognitionView'];
export type ThresholdValues = S['ThresholdValues'];
export type ImpactView = S['RecognitionViews.ImpactView'];
export type EnrolmentSettingsView = S['RecognitionViews.EnrolmentView'];

export function useRecognitionSettings() {
  return useQuery({
    queryKey: settings('recognition'),
    queryFn: ({ signal }) => request<RecognitionView>('/settings/recognition', { signal }),
  });
}

/** The last days' face scores split again with these values (the impact preview). */
export function useImpact(values: { accept: number; reviewBand: number; margin: number }) {
  return useQuery({
    queryKey: [...settings('recognition'), 'impact', values],
    queryFn: ({ signal }) => request<ImpactView>('/settings/recognition/impact', { signal, query: values }),
    placeholderData: keepPreviousData,
  });
}

/** Saves the thresholds as a new profile (TP-n) and the changed per-camera liveness flags. */
export function useSaveRecognition() {
  return useSave(
    settings('recognition'),
    async ({
      thresholds,
      liveness,
    }: {
      thresholds: S['RecognitionController.SaveThresholdsRequest'] | null;
      liveness: readonly { cameraCode: string; enabled: boolean }[];
    }) => {
      for (const l of liveness) {
        await request<unknown>(`/settings/recognition/liveness/${l.cameraCode}`, {
          method: 'PUT',
          body: { enabled: l.enabled },
        });
      }
      return thresholds
        ? request<RecognitionView>('/settings/recognition', { method: 'PUT', body: thresholds })
        : request<RecognitionView>('/settings/recognition');
    },
    [queryKeys.cameras, queryKeys.live],
  );
}

export function useActivateProfile() {
  return useSave(settings('recognition'), (code: string) =>
    request<RecognitionView>(`/settings/recognition/profiles/${code}/activate`, { method: 'POST' }),
  );
}

export function useEnrolmentSettings() {
  return useQuery({
    queryKey: settings('enrolment'),
    queryFn: ({ signal }) => request<EnrolmentSettingsView>('/settings/enrolment', { signal }),
  });
}

export function useSaveEnrolmentSettings() {
  return useSave(
    settings('enrolment'),
    (body: S['RecognitionController.UpdateEnrolmentRequest']) =>
      request<EnrolmentSettingsView>('/settings/enrolment', { method: 'PUT', body }),
    [queryKeys.enrolment],
  );
}

// Attendance rules

export type RulesView = S['SettingsController.RulesView'];
export type AttendanceRules = S['AttendanceRulesSettings'];
export type RuleTest = S['RuleTesterController.TestRequest'];
export type RuleTestResult = S['RuleTesterService.Result'];

export function useRules() {
  return useQuery({
    queryKey: settings('rules'),
    queryFn: ({ signal }) => request<RulesView>('/settings/rules', { signal }),
  });
}

/** Saved rules change today's statuses and what goes to KRZ HRMS. */
export function useSaveRules() {
  return useSave(
    settings('rules'),
    (body: S['SettingsController.UpdateRulesRequest']) =>
      request<RulesView>('/settings/rules', { method: 'PUT', body }),
    [queryKeys.meta, queryKeys.attendance, queryKeys.crossings, queryKeys.dashboard],
  );
}

/** The rule tester: the same engine on a scenario day, with the page's rules (saved or not); nothing is stored. */
export function useRuleTest(test: RuleTest | null) {
  return useQuery({
    queryKey: [...settings('rules'), 'test', test],
    queryFn: ({ signal }) =>
      request<RuleTestResult>('/settings/rules/test', { method: 'POST', body: test, signal, userActivity: false }),
    enabled: test !== null && test.rows.length > 0,
    placeholderData: keepPreviousData,
    staleTime: Infinity,
  });
}

// KRZ HRMS

export type HrmsOverview = S['HrmsViews.Overview'];
export type OutboxEntry = S['HrmsViews.Entry'];

export function useHrms() {
  return useQuery({
    queryKey: settings('hrms'),
    queryFn: ({ signal }) => request<HrmsOverview>('/settings/hrms', { signal }),
  });
}

export function useOutbox(query: Record<string, QueryValue>) {
  return useQuery({
    queryKey: [...settings('hrms'), 'outbox', query],
    queryFn: ({ signal }) => request<S['PageResponseEntry']>('/settings/hrms/outbox', { signal, query }),
    placeholderData: keepPreviousData,
  });
}

/** Retry one failed entry, every failed entry, or release the punches held while HRMS was not connected. */
export function useOutboxAction() {
  return useChange(
    (action: { retry: string } | 'retry-failed' | 'release-held') =>
      typeof action === 'string'
        ? request<S['HrmsViews.Requeued']>(`/settings/hrms/outbox/${action}`, { method: 'POST' }).then(r => r.entries)
        : request<OutboxEntry>(`/settings/hrms/outbox/${action.retry}/retry`, { method: 'POST' }).then(() => 1),
    [settings('hrms'), queryKeys.attendance, queryKeys.dashboard, queryKeys.statusSummary],
  );
}

// Cameras

export type CameraView = S['CameraViews.CameraView'];
export type CameraInput = S['CameraInput'];
export type CameraGeometry = S['CameraController.GeometryRequest'];
export type CameraTest = S['CameraViews.TestResult'];
export type Discovered = S['CameraViews.DiscoveredCamera'];
export type CameraHealth = S['CameraViews.HealthView'];
export type Calibration = S['CameraViews.CalibrationView'];
export type Wire = S['Wire'];

const CAMERAS: readonly QueryKey[] = [queryKeys.cameras, queryKeys.live, queryKeys.statusSummary, queryKeys.system];

export function useCameras() {
  return useQuery({
    queryKey: [...queryKeys.cameras, 'list'],
    queryFn: ({ signal }) => request<CameraView[]>('/settings/cameras', { signal }),
  });
}

export function useCamera(code: string | null) {
  return useQuery({
    queryKey: [...queryKeys.cameras, 'one', code],
    queryFn: ({ signal }) => request<CameraView>(`/settings/cameras/${code ?? ''}`, { signal }),
    enabled: code !== null,
  });
}

/**
 * Add a camera (no code), or save its details and then its geometry. Each answer carries the next version; the
 * geometry alone (only the tripwire or zone changed) goes with the version the editor loaded.
 */
export function useSaveCamera() {
  return useChange(
    async ({
      code,
      version,
      input,
      geometry,
    }: {
      code: string | null;
      /** The version the editor loaded (null for a new camera). */
      version: number | null;
      input: CameraInput | null;
      geometry: Omit<CameraGeometry, 'version'> | null;
    }) => {
      if (code === null) {
        return request<CameraView>('/settings/cameras', { method: 'POST', body: input });
      }
      let saved = input ? await request<CameraView>(`/settings/cameras/${code}`, { method: 'PUT', body: input }) : null;
      if (geometry) {
        saved = await request<CameraView>(`/settings/cameras/${code}/geometry`, {
          method: 'PUT',
          body: { ...geometry, version: saved?.version ?? version ?? 0 },
        });
      }
      return saved;
    },
    CAMERAS,
  );
}

export function useCameraPassword(code: string) {
  return useChange(
    (password: string) =>
      request<CameraView>(`/settings/cameras/${code}/password`, { method: 'PUT', body: { password } }),
    CAMERAS,
  );
}

export function useTestCamera(code: string) {
  return useChange(() => request<CameraTest>(`/settings/cameras/${code}/test`, { method: 'POST' }), CAMERAS);
}

export function useDiscoverCameras() {
  return useMutation({
    mutationFn: () => request<Discovered[]>('/settings/cameras/discover', { method: 'POST', body: { subnet: null } }),
  });
}

export function useCameraHealth(code: string | null) {
  return useQuery({
    queryKey: [...queryKeys.cameras, 'health', code],
    queryFn: ({ signal }) => request<CameraHealth>(`/settings/cameras/${code ?? ''}/health`, { signal }),
    enabled: code !== null,
  });
}

export function useStartCalibration(code: string) {
  return useMutation({
    mutationFn: () =>
      request<Calibration>(`/settings/cameras/${code}/calibration`, { method: 'POST', body: { durationS: 60 } }),
  });
}

/** A test walk's progress, asked every 1.5 s while it runs. */
export function useCalibration(code: string, runId: number | null) {
  return useQuery({
    queryKey: [...queryKeys.cameras, 'calibration', code, runId],
    queryFn: ({ signal }) => request<Calibration>(`/settings/cameras/${code}/calibration/${String(runId)}`, { signal }),
    enabled: runId !== null,
    refetchInterval: query => (query.state.data?.status === 'RUNNING' || !query.state.data ? 1_500 : false),
  });
}
