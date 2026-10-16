/**
 * The one way the app calls the backend (F4): same-origin fetch under /api/v1, CSRF on unsafe methods, the error
 * envelope parsed into ApiError, a 30 s timeout, and X-User-Activity only on user-initiated requests (security §3:
 * the idle timeout must not be kept alive by polling, SSE or background refetches).
 */
export const API_BASE = '/api/v1';
const TIMEOUT_MS = 30_000;
/** A query that starts within this time after a navigation or an explicit refresh counts as the user's. */
const ACTIVITY_WINDOW_MS = 2_000;
const UNSAFE = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** The backend's error envelope (common/error/ErrorResponse; not part of the OpenAPI components). */
export interface FieldError {
  field: string;
  message: string;
}
interface ErrorBody {
  code: string;
  message: string;
  fieldErrors: FieldError[];
  traceId: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: readonly FieldError[];
  readonly traceId: string | null;

  constructor(status: number, body: Partial<ErrorBody>) {
    super(body.message ?? `Request failed (HTTP ${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.code = body.code ?? 'UNEXPECTED';
    this.fieldErrors = body.fieldErrors ?? [];
    this.traceId = body.traceId ?? null;
  }
}

/** 401: the session ended or never existed. */
export class UnauthorizedError extends ApiError {}
/** 403: the user lacks the permission. */
export class ForbiddenError extends ApiError {}
/** 409 STALE_VERSION: someone else changed the record; forms offer a reload. */
export class StaleVersionError extends ApiError {}
/** 429: rate limited; retryAfterSeconds from the Retry-After header. */
export class RateLimitedError extends ApiError {
  readonly retryAfterSeconds: number;
  constructor(status: number, body: Partial<ErrorBody>, retryAfterSeconds: number) {
    super(status, body);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export const STALE_VERSION_MESSAGE = 'Someone else changed this. Reload to see the latest.';

let lastUserActivity = 0;
/** Navigations and explicit refreshes call this; the queries they start carry X-User-Activity. */
export function markUserActivity(now: number = Date.now()): void {
  lastUserActivity = now;
}

const activitySent = new Set<() => void>();
/** Called whenever a request carrying X-User-Activity is sent: the server has just extended the idle timer. */
export function onUserActivitySent(listener: () => void): () => void {
  activitySent.add(listener);
  return () => activitySent.delete(listener);
}

type Handler = (error: ApiError) => void;
const handlers: { unauthorized: Handler | null; rateLimited: Handler | null } = {
  unauthorized: null,
  rateLimited: null,
};
/** The app wires the 401 redirect and the 429 toast here (app/providers.tsx). */
export function onApiError(kind: keyof typeof handlers, handler: Handler): void {
  handlers[kind] = handler;
}

export type QueryValue = string | number | boolean | null | undefined | readonly (string | number)[];

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** JSON-serialised, or sent as-is when it is FormData. */
  body?: unknown;
  query?: Record<string, QueryValue>;
  signal?: AbortSignal;
  /** Overrides the activity rule: mutations count as activity, queries only right after a user action. */
  userActivity?: boolean;
  /** 401 is the expected answer (/me before sign-in): no redirect. */
  allowUnauthorized?: boolean;
  /** Outside /api/v1 (POST /logout). */
  root?: boolean;
  /** Images (face crops, snapshots): the body as a Blob. */
  responseType?: 'json' | 'blob';
}

export function apiUrl(path: string, query?: Record<string, QueryValue>, root = false): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === null || value === undefined || value === '') continue;
    if (Array.isArray(value)) {
      for (const v of value) params.append(key, String(v));
    } else params.set(key, String(value));
  }
  const qs = params.toString();
  return `${root ? '' : API_BASE}${path}${qs ? `?${qs}` : ''}`;
}

function csrfToken(): string | null {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie);
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

async function errorBody(response: Response): Promise<Partial<ErrorBody>> {
  try {
    return (await response.json()) as Partial<ErrorBody>;
  } catch {
    return {};
  }
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const headers = new Headers({ Accept: options.responseType === 'blob' ? '*/*' : 'application/json' });
  let body: BodyInit | undefined;
  if (options.body instanceof FormData) body = options.body;
  else if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(options.body);
  }
  if (UNSAFE.has(method)) {
    const token = csrfToken();
    if (token) headers.set('X-XSRF-TOKEN', token);
  }
  const activity = options.userActivity ?? (UNSAFE.has(method) || Date.now() - lastUserActivity < ACTIVITY_WINDOW_MS);
  if (activity) {
    headers.set('X-User-Activity', '1');
    activitySent.forEach(listener => {
      listener();
    });
  }

  const timeout = AbortSignal.timeout(TIMEOUT_MS);
  const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout;
  const response = await fetch(apiUrl(path, options.query, options.root), {
    method,
    headers,
    body: body ?? null,
    credentials: 'same-origin',
    signal,
  });

  if (response.ok) {
    if (response.status === 204) return undefined as T;
    if (options.responseType === 'blob') return (await response.blob()) as T;
    const type = response.headers.get('Content-Type') ?? '';
    return (type.includes('json') ? await response.json() : await response.text()) as T;
  }
  const error = await errorBody(response);
  switch (response.status) {
    case 401: {
      const e = new UnauthorizedError(401, error);
      if (!options.allowUnauthorized) handlers.unauthorized?.(e);
      throw e;
    }
    case 403:
      throw new ForbiddenError(403, error);
    case 409:
      throw error.code === 'STALE_VERSION' ? new StaleVersionError(409, error) : new ApiError(409, error);
    case 429: {
      const e = new RateLimitedError(429, error, Number(response.headers.get('Retry-After') ?? '0') || 0);
      handlers.rateLimited?.(e);
      throw e;
    }
    default:
      throw new ApiError(response.status, error);
  }
}

const RETURN_TO_CHARS = /^\/[A-Za-z0-9/_\-.]*(\?[A-Za-z0-9_\-.=&%,]*)?$/;
const RETURN_TO_ROUTES = [
  /^\/$/,
  /^\/(dashboard|live|review|events|enrol)$/,
  /^\/attendance\/(today|log)$/,
  /^\/employees(\/[0-9a-fA-F-]{36})?$/,
  /^\/org\/(locations|departments|shifts|allocations|holidays)$/,
  /^\/reports\/(coverage|recognition)$/,
  /^\/settings(\/[a-z-]+)?$/,
];

/**
 * The backend's ReturnToValidator, rule for rule: a relative path with a single leading "/", no scheme, "//",
 * backslash or "..", safe characters only, and one of the app's routes. Anything else is null (open redirects).
 */
export function safeReturnTo(value: string | null | undefined): string | null {
  if (!value || value.length > 512) return null;
  if (value.startsWith('//') || value.includes('\\') || value.includes('://')) return null;
  if (!RETURN_TO_CHARS.test(value)) return null;
  const path = value.split('?')[0] ?? '';
  if (path.includes('..') || path.includes('//')) return null;
  return RETURN_TO_ROUTES.some(route => route.test(path)) ? value : null;
}
