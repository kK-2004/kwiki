/** API contracts shared by the workspace and authentication pages. */
export interface TreeNodeDto {
  id: number;
  uuid: string;
  title: string;
  nodeType: 'PAGE' | 'FOLDER';
  parentId?: number | null;
  children: TreeNodeDto[];
}

export interface PageDto {
  revisionNo: number;
  markdown: string;
  html: string;
  createdBy: number;
  createdAt: string;
  title?: string;
  canEdit?: boolean;
  canManage?: boolean;
}

export interface UserDto {
  id: number;
  username: string;
  displayName?: string;
  email?: string;
  admin: boolean;
}

export interface ApiError {
  status: number;
  code: string;
  message?: string;
  details?: unknown;
}

export interface ApiEnvelope<T> {
  code: number;
  success: boolean;
  message?: string;
  data?: T;
}

type AuthState = { token: string; generation: number };
const STORAGE_KEY = 'kwiki.auth.token';
const authState: AuthState = {
  token: typeof sessionStorage === 'undefined' ? '' : sessionStorage.getItem(STORAGE_KEY) ?? '',
  generation: 0,
};

export function setAuthToken(token: string): void {
  const currentIssuedAt = tokenIssuedAt(authState.token);
  const incomingIssuedAt = tokenIssuedAt(token);
  if (token && currentIssuedAt !== null && incomingIssuedAt !== null && incomingIssuedAt < currentIssuedAt) return;
  authState.token = token;
  if (typeof sessionStorage !== 'undefined') {
    if (token) sessionStorage.setItem(STORAGE_KEY, token);
    else sessionStorage.removeItem(STORAGE_KEY);
  }
}

export function getAuthToken(): string { return authState.token; }
export function getAuthGeneration(): number { return authState.generation; }
export function bumpAuthGeneration(): number { return ++authState.generation; }
export function clearAuthToken(): void { setAuthToken(''); bumpAuthGeneration(); }

function tokenIssuedAt(token: string): number | null {
  try {
    if (!token) return null;
    const payload = token.split('.')[1];
    const decoded = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
    return typeof decoded.iat === 'number' ? decoded.iat : null;
  } catch { return null; }
}

export class ApiClient {
  constructor(private readonly baseUrl = '/api/v1') {}

  private async request(path: string, init: RequestInit = {}, signal?: AbortSignal): Promise<Response> {
    const requestGeneration = authState.generation;
    const isFormData = typeof FormData !== 'undefined' && init.body instanceof FormData;
    const headers = new Headers(init.headers);
    if (!isFormData && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    if (path.startsWith('/notifications')) headers.set('X-Kwiki-Maintenance', 'true');
    if (authState.token) headers.set('Authorization', `Bearer ${authState.token}`);
    const response = await fetch(this.baseUrl + path, { ...init, signal, headers });
    const renewed = response.headers.get('X-Auth-Token');
    if (renewed && requestGeneration === authState.generation) setAuthToken(renewed);
    if (response.status === 401) {
      clearAuthToken();
      if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('kwiki:unauthenticated'));
      throw { status: 401, code: 'unauthenticated' } satisfies ApiError;
    }
    if (!response.ok) {
      const body = await response.json().catch(() => null);
      throw { status: response.status, code: response.status === 403 ? 'forbidden' : `http_${response.status}`, message: body?.message ?? body?.detail } satisfies ApiError;
    }
    return response;
  }

  private async body<T>(response: Response): Promise<T> {
    if (response.status === 204) return undefined as T;
    const value = (await response.json()) as T | ApiEnvelope<T>;
    if (isEnvelope(value)) {
      if (!value.success || value.code >= 400) {
        throw { status: value.code, code: value.message ?? 'request_failed', message: value.message } satisfies ApiError;
      }
      return value.data as T;
    }
    return value as T;
  }

  async json<T>(path: string, signal?: AbortSignal): Promise<T> {
    return this.body<T>(await this.request(path, { method: 'GET' }, signal));
  }

  async post<T = unknown>(path: string, body?: unknown, signal?: AbortSignal, extraHeaders?: HeadersInit): Promise<T> {
    return this.body<T>(await this.request(path, {
      method: 'POST',
      body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body),
      headers: extraHeaders,
    }, signal));
  }

  async put<T = unknown>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
    return this.body<T>(await this.request(path, {
      method: 'PUT', body: body instanceof FormData ? body : JSON.stringify(body),
    }, signal));
  }

  async patch<T = unknown>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
    return this.body<T>(await this.request(path, {
      method: 'PATCH', body: body instanceof FormData ? body : JSON.stringify(body),
    }, signal));
  }

  async delete<T = unknown>(path: string, signal?: AbortSignal): Promise<T> {
    return this.body<T>(await this.request(path, { method: 'DELETE' }, signal));
  }

  async upload<T = unknown>(path: string, form: FormData, signal?: AbortSignal, idempotencyKey?: string): Promise<T> {
    return this.body<T>(await this.request(path, {
      method: 'POST',
      body: form,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    }, signal));
  }
}

function isEnvelope<T>(value: unknown): value is ApiEnvelope<T> {
  return typeof value === 'object' && value !== null && 'success' in value && 'code' in value;
}

export const api = new ApiClient();

export function errorMessage(error: unknown, fallback = '操作失败，请稍后重试'): string {
  const value = error as Partial<ApiError> | null;
  if (value?.status === 401) return '登录已过期，请重新登录';
  if (value?.status === 403) return '你没有执行此操作的权限';
  if (value?.status === 413) return '文件过大，请选择不超过 20 MB 的文件';
  if (value?.status === 404) return '内容不存在或你没有访问权限';
  if (value?.message && !/sql|jdbc|exception|password|secret|stack/i.test(value.message)) return value.message;
  if (value?.status && value.status >= 500) return '服务暂时不可用，请稍后重试';
  return fallback;
}
