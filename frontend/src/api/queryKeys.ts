/**
 * The single factory of TanStack Query keys (F4). Keys are hierarchical: invalidating a prefix (e.g. ['crossings'])
 * refreshes every list and detail under it. Realtime events invalidate by these prefixes.
 */
export const queryKeys = {
  me: ['me'] as const,
  meta: ['meta'] as const,
  branding: ['branding'] as const,
  permissions: ['permissions'] as const,
  statusSummary: ['system', 'status', 'summary'] as const,
  system: ['system'] as const,
  notifications: ['notifications'] as const,
  review: ['review'] as const,
  reviewCounts: ['review', 'counts'] as const,
  search: (q: string) => ['search', q] as const,
  dashboard: ['dashboard'] as const,
  crossings: ['crossings'] as const,
  live: ['live'] as const,
  attendance: ['attendance'] as const,
  employees: ['employees'] as const,
  enrolment: ['enrolment'] as const,
  organisation: ['organisation'] as const,
  reports: ['reports'] as const,
  settings: ['settings'] as const,
  audit: ['audit'] as const,
  accessRequests: ['settings', 'access-requests'] as const,
  cameras: ['settings', 'cameras'] as const,
} as const;
