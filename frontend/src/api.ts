import type {
  ConversationDetail,
  ConversationSummary,
  FsList,
  Health,
  RunResponse,
  Scenario,
  Snapshot,
  StreamEvent,
} from './types';

const TOKEN_KEY = 'auth.token';

/** 非 2xx 一律抛这个：页面据此区分「要口令」与「别的错」。 */
export class HttpError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || '';
}

export function setToken(token: string) {
  const trimmed = token.trim();
  if (trimmed) localStorage.setItem(TOKEN_KEY, trimmed);
  else localStorage.removeItem(TOKEN_KEY);
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: 'Bearer ' + token } : {};
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: { ...authHeaders(), ...(init?.headers || {}) },
  });
  if (!res.ok) {
    let message = res.status + ' ' + res.statusText;
    try {
      const body = (await res.json()) as { error?: string };
      if (body?.error) message = body.error;
    } catch {
      /* 后端非 JSON 响应时保留状态码文案 */
    }
    throw new HttpError(res.status, message);
  }
  return (await res.json()) as T;
}

export function fetchHealth(): Promise<Health> {
  return request<Health>('/api/health');
}

export function fetchScenarios(): Promise<Scenario[]> {
  return request<Scenario[]>('/api/scenarios');
}

export function fetchSnapshots(): Promise<Snapshot[]> {
  return request<Snapshot[]>('/api/snapshots');
}

/** 最近几段对话的摘要（服务端已落库，重启后仍在）。 */
export function fetchConversations(): Promise<ConversationSummary[]> {
  return request<ConversationSummary[]>('/api/conversations');
}

/** 取一段对话的完整消息；不存在时抛 404 的 HttpError，调用方据此开新对话。 */
export function fetchConversation(conversationId: string): Promise<ConversationDetail> {
  return request<ConversationDetail>(
    '/api/conversations?conversationId=' + encodeURIComponent(conversationId),
  );
}

export function fetchFsList(path?: string): Promise<FsList> {
  const url =
    '/api/fs/list' + (path ? '?path=' + encodeURIComponent(path) : '');
  return request<FsList>(url);
}

/**
 * 发起到后端的新运行 / 续跑。返回 sessionId 供后续 EventSource 订阅。
 * workspace 为空表示用服务端配置的默认沙箱。
 */
export function startRun(params: {
  task: string;
  conversationId?: string;
  workspace?: string | null;
  scenario?: string;
  maxRounds?: number;
}): Promise<RunResponse> {
  const q = new URLSearchParams({ task: params.task });
  if (params.conversationId) q.set('conversationId', params.conversationId);
  if (params.workspace) q.set('workspace', params.workspace);
  q.set('scenario', params.scenario ?? 'auto');
  if (params.maxRounds != null) q.set('maxRounds', String(params.maxRounds));
  return request<RunResponse>('/api/run?' + q, { method: 'POST' });
}

export function resumeRun(params: {
  file: string;
  scenario: string;
  maxRounds?: number;
  workspace?: string | null;
}): Promise<RunResponse> {
  const q = new URLSearchParams({ file: params.file, scenario: params.scenario });
  if (params.maxRounds != null) q.set('maxRounds', String(params.maxRounds));
  if (params.workspace) q.set('workspace', params.workspace);
  return request<RunResponse>('/api/resume?' + q, { method: 'POST' });
}

export async function stopRun(sessionId: string): Promise<void> {
  await request<{ ok: boolean }>(
    '/api/stop?sessionId=' + encodeURIComponent(sessionId),
    { method: 'POST' },
  );
}

/** 打开 SSE 订阅；返回关闭函数。 */
export function openStream(
  sessionId: string,
  onEvent: (e: StreamEvent) => void,
  onError: (e: HttpError | null) => void,
): () => void {
  // EventSource 不支持自定义请求头，口令只能放查询参数（服务端同时接受 header 与 ?token=）
  const token = getToken();
  const url =
    '/api/stream?sessionId=' +
    encodeURIComponent(sessionId) +
    (token ? '&token=' + encodeURIComponent(token) : '');
  const es = new EventSource(url);
  es.onmessage = (ev) => {
    try {
      onEvent(JSON.parse(ev.data) as StreamEvent);
    } catch {
      /* 忽略无法解析的心跳/脏数据 */
    }
  };
  es.onerror = () => {
    es.close();
    // 401 时 EventSource 只会给一个空的 error 事件，这里无法区分，交给上层重新探一次健康
    onError(null);
  };
  return () => es.close();
}
