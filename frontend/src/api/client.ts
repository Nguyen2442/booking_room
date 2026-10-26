const TOKEN_KEY = 'booking.token';

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fieldErrors: Record<string, string> = {},
    /** the full problem+json body: carries details such as `conflicts` */
    public problem: Record<string, any> = {},
  ) {
    super(message);
  }
}

export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set(token: string | null) {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token);
      else localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* storage unavailable (private mode): session simply will not persist */
    }
  },
};

let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

type Query = Record<string, string | number | boolean | string[] | undefined | null>;

interface Options {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  query?: Query;
  signal?: AbortSignal;
}

function buildUrl(path: string, query?: Query) {
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) v.forEach((x) => params.append(k, x));
    else params.append(k, String(v)); // URLSearchParams encodes '+' in offsets correctly
  }
  const qs = params.toString();
  return `/api/v1${path}${qs ? `?${qs}` : ''}`;
}

export async function api<T = void>(path: string, opts: Options = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';

  let res: Response;
  try {
    res = await fetch(buildUrl(path, opts.query), {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal: opts.signal,
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the server. Check your connection and try again.');
  }

  if (res.status === 204) return undefined as T;

  let data: any = null;
  try {
    data = await res.json();
  } catch {
    /* empty or non-JSON body */
  }

  if (!res.ok) {
    if (res.status === 401 && !path.startsWith('/auth/login')) onUnauthorized();
    const fieldErrors: Record<string, string> = {};
    for (const e of data?.errors ?? []) if (e.field && !fieldErrors[e.field]) fieldErrors[e.field] = e.message;
    const fallback = res.status >= 500 ? 'Something went wrong on our side. Please try again.' : 'Request failed.';
    // errors produced by nginx or a gateway have no JSON body: map them to codes the UI can translate
    const proxyCode = res.status === 429 ? 'RATE_LIMITED' : res.status === 413 ? 'PAYLOAD_TOO_LARGE'
      : [502, 503, 504].includes(res.status) ? 'SERVICE_UNAVAILABLE' : `HTTP_${res.status}`;
    throw new ApiError(res.status, data?.code ?? proxyCode, data?.detail ?? fallback, fieldErrors, data ?? {});
  }
  return data as T;
}

