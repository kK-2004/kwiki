/** Typed API client: stale-request cancellation and normalized error mapping. */
export interface TreeNodeDto {
  id: number;
  uuid: string;
  title: string;
  nodeType: 'PAGE' | 'FOLDER';
  children: TreeNodeDto[];
}

export interface PageDto {
  revisionNo: number;
  markdown: string;
  html: string;
  createdBy: number;
  createdAt: string;
}

export interface ApiError {
  status: number;
  code: string;
}

const authStore = { token: '' };

export function setAuthToken(token: string) {
  authStore.token = token;
}

export class ApiClient {
  constructor(private readonly baseUrl = '/api/v1') {}

  private async request(path: string, init: RequestInit, signal?: AbortSignal): Promise<Response> {
    const response = await fetch(this.baseUrl + path, {
      ...init,
      signal,
      headers: {
        'Content-Type': 'application/json',
        ...(authStore.token ? { Authorization: `Bearer ${authStore.token}` } : {}),
        ...(init.headers ?? {}),
      },
    });
    if (response.status === 401) {
      throw { status: 401, code: 'unauthenticated' } satisfies ApiError;
    }
    if (response.status === 403) {
      throw { status: 403, code: 'forbidden' } satisfies ApiError;
    }
    if (!response.ok) {
      throw { status: response.status, code: `http_${response.status}` } satisfies ApiError;
    }
    return response;
  }

  async json<T>(path: string, signal?: AbortSignal): Promise<T> {
    const response = await this.request(path, { method: 'GET' }, signal);
    return (await response.json()) as T;
  }

  async post(path: string, body: unknown): Promise<unknown> {
    const response = await this.request(path, {
      method: 'POST',
      body: JSON.stringify(body),
    });
    return response.status === 204 ? null : response.json();
  }

  async put(path: string, body: unknown): Promise<unknown> {
    const response = await this.request(path, {
      method: 'PUT',
      body: JSON.stringify(body),
    });
    return response.json();
  }
}

export const api = new ApiClient();
