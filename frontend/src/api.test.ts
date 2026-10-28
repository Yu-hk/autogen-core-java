// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  HttpError,
  fetchHealth,
  getToken,
  openStream,
  setToken,
  startRun,
} from './api';

/**
 * 这些用例守的是「页面和后端之间的约定」，不是实现细节。
 *
 * <p>两条最要紧：口令存在哪儿（存错地方刷新就丢，用户每次都要重输），
 * 以及 SSE 断线时的状态判定（判错就会把一次网络抖动变成「连接断开」，
 * 而服务端明明能接着补发）。</p>
 */

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 401 ? 'Unauthorized' : 'Error',
    json: async () => body,
  } as unknown as Response;
}

/** 最小的 EventSource 替身：只记录 URL、readyState 与 close 调用。 */
class FakeEventSource {
  static CLOSED = 2;
  static CONNECTING = 0;
  static instances: FakeEventSource[] = [];

  url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  closeCalls = 0;

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  close() {
    this.closeCalls += 1;
    this.readyState = FakeEventSource.CLOSED;
  }
}

beforeEach(() => {
  localStorage.clear();
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('口令存取', () => {
  it('存进去能读出来，并去掉两头空白', () => {
    setToken('  abc123  ');
    expect(getToken()).toBe('abc123');
  });

  it('存空串等于清除', () => {
    setToken('abc');
    setToken('   ');
    expect(getToken()).toBe('');
    // 留着空串会让 authHeaders 生成 "Bearer "，后端会当成"给了口令但不匹配"
    expect(localStorage.getItem('auth.token')).toBeNull();
  });
});

describe('请求', () => {
  it('有口令时带上 Authorization', async () => {
    setToken('t-1');
    const fetchMock = vi.fn(async () => jsonResponse({ ok: true }));
    vi.stubGlobal('fetch', fetchMock);

    await fetchHealth();

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer t-1');
  });

  it('没口令时不带空的 Authorization', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ ok: true }));
    vi.stubGlobal('fetch', fetchMock);

    await fetchHealth();

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('401 变成可识别的 HttpError，且用服务端原文提示', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      jsonResponse({ error: '需要访问口令：在页面右上角填入' }, 401)));

    // 页面靠 status 决定"弹闸门"还是"显示错误"，所以状态码必须原样保留
    await expect(fetchHealth()).rejects.toMatchObject({
      status: 401,
      message: '需要访问口令：在页面右上角填入',
    });
  });

  it('响应不是 JSON 时退回状态码文案，而不是抛解析异常', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 502,
      statusText: 'Bad Gateway',
      json: async () => {
        throw new Error('not json');
      },
    } as unknown as Response)));

    const error = await fetchHealth().catch((e) => e);
    expect(error).toBeInstanceOf(HttpError);
    expect(error.status).toBe(502);
    expect(error.message).toContain('502');
  });

  it('发起运行的参数要能经得住中文与空格', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ sessionId: 's1', conversationId: 'c1' }));
    vi.stubGlobal('fetch', fetchMock);

    await startRun({ task: '看一下 频率协调 的日志', scenario: 'auto' });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.method).toBe('POST');
    // 用 URLSearchParams 解析回来，而不是在字符串里找：空格会被编码成 +，
    // 直接拿原文去比会假失败（这条断言自己踩过一次）
    const query = new URL(url, 'http://localhost').searchParams;
    expect(query.get('task')).toBe('看一下 频率协调 的日志');
    expect(query.get('scenario')).toBe('auto');
  });
});

describe('SSE 连接', () => {
  it('口令放查询参数（EventSource 加不了请求头）', () => {
    setToken('sse-token');
    openStream('sess-1', () => {}, () => {});

    const url = FakeEventSource.instances[0].url;
    expect(url).toContain('sessionId=sess-1');
    expect(url).toContain('token=sse-token');
  });

  it('收到 expired 时自己关掉，避免重连又拿到 expired 的死循环', () => {
    setToken('t');
    const events: string[] = [];
    openStream('sess-1', (e) => events.push(e.kind), () => {});

    const es = FakeEventSource.instances[0];
    es.onmessage!({ data: JSON.stringify({ kind: 'expired', message: '失效了' }) });

    expect(events).toEqual(['expired']);
    expect(es.closeCalls).toBe(1);
  });

  it('普通事件不关闭连接', () => {
    setToken('t');
    openStream('sess-1', () => {}, () => {});
    const es = FakeEventSource.instances[0];

    es.onmessage!({ data: JSON.stringify({ kind: 'delta', text: '你' }) });
    es.onmessage!({ data: JSON.stringify({ kind: 'delta', text: '好' }) });

    expect(es.closeCalls).toBe(0);
  });

  it('parse 失败的帧被忽略，不影响后续事件', () => {
    setToken('t');
    const events: string[] = [];
    openStream('sess-1', (e) => events.push(e.kind), () => {});
    const es = FakeEventSource.instances[0];

    es.onmessage!({ data: '不是 JSON' });
    es.onmessage!({ data: JSON.stringify({ kind: 'done' }) });

    expect(events).toEqual(['done']);
  });

  it('onerror 区分「正在重连」与「彻底断了」', () => {
    setToken('t');
    const states: string[] = [];
    openStream('sess-1', () => {}, (s) => states.push(s));
    const es = FakeEventSource.instances[0];

    // CONNECTING：浏览器自己在重连，会带上 Last-Event-ID，这边不能关
    es.readyState = FakeEventSource.CONNECTING;
    es.onerror!();
    expect(states).toEqual(['reconnecting']);
    expect(es.closeCalls).toBe(0);

    // CLOSED：浏览器放弃了，这时候才该让页面显示"连接断开"
    es.readyState = FakeEventSource.CLOSED;
    es.onerror!();
    expect(states).toEqual(['reconnecting', 'closed']);
  });
});
