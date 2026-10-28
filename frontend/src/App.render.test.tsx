// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import App from './App';

/**
 * 页面骨架的契约测试。
 *
 * <p>这 22 个 id 不是随便起的：外部自动化脚本（Playwright 冒烟、巡检脚本）按它们找元素。
 * 前端从单页改成 React 时特意保留了这些 id，就是为了让脚本不用跟着改。它们的价值在于
 * 「不能变」，而"不能变"只有写成断言才成立——否则某次重构顺手改成 className，
 * 要到别人的脚本跑挂了才发现。</p>
 *
 * <p>不引入 @testing-library/react：这里只验证"结构在不在"，用 react-dom 直接渲染
 * 就够，少一个依赖也少一处版本冲突。</p>
 */

/** 首屏就该在的：脚本一打开页面就要能点到它们。 */
const FIRST_SCREEN_IDS = [
  'taskInput',
  'runBtn',
  'stopBtn',
  'newChatBtn',
  'wsPath',
  'samples',
  'members',
  'advanced',
  'scenes',
  'maxRounds',
  'thread',
  'phase',
  'placeholder',
  'statRound',
  'statMsg',
  'statDelta',
  'statTool',
  'stopReason',
  'timeline',
  'snapshots',
  'healthDot',
  'healthText',
  'modelChip',
];

/** 工作目录弹层里的，点了「工作目录」才出现。 */
const PICKER_IDS = [
  'wsModal',
  'wsClose',
  'wsUp',
  'wsInput',
  'wsGo',
  'wsCur',
  'wsList',
  'wsWarn',
  'wsUse',
];

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'OK',
    json: async () => body,
  } as unknown as Response;
}

/** 按接口分派返回，让首屏的几个并发请求都拿到形状正确的数据。 */
function stubBackend() {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.includes('/api/health')) {
      return jsonResponse({
        ok: true,
        port: 8097,
        provider: 'ollama',
        model: 'qwen2.5:3b',
        baseUrl: 'http://127.0.0.1:11434/v1',
        ready: true,
        detail: '模型在线',
      });
    }
    if (url.includes('/api/scenarios')) return jsonResponse([]);
    if (url.includes('/api/snapshots')) return jsonResponse([]);
    if (url.includes('/api/conversations')) return jsonResponse([]);
    if (url.includes('/api/fs/list')) {
      return jsonResponse({ path: '', parent: '', writable: true, total: 0, truncated: false, entries: [] });
    }
    return jsonResponse({});
  }));
}

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
  // React 18 要求显式声明"我在测试环境里用 act"，否则每次 setState 都会刷警告
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  stubBackend();
  vi.stubGlobal('EventSource', class {
    static CLOSED = 2;
    onmessage: unknown = null;
    onerror: unknown = null;
    onopen: unknown = null;
    close() {}
  });
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it('首屏渲染出全部关键元素', async () => {
  await act(async () => {
    root.render(<App />);
  });

  const missing = FIRST_SCREEN_IDS.filter((id) => document.getElementById(id) === null);
  expect(missing, '这些 id 不见了，外部自动化脚本会立刻挂').toEqual([]);
});

it('点「工作目录」能打开选择弹层', async () => {
  await act(async () => {
    root.render(<App />);
  });

  const trigger = document.getElementById('wsPath');
  expect(trigger).not.toBeNull();
  await act(async () => {
    trigger!.click();
    await Promise.resolve();
  });

  const missing = PICKER_IDS.filter((id) => document.getElementById(id) === null);
  expect(missing, '弹层里的 id 不见了，选目录的脚本会点不到').toEqual([]);
});

it('没配口令时先显示口令闸门，而不是一堆报错', async () => {
  // 服务端要口令时页面得有个地方输入，否则用户只能看着 401 干瞪眼
  vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ error: '需要访问口令' }, 401)));

  await act(async () => {
    root.render(<App />);
    // 让首屏那几个请求的 catch 分支跑完
    await Promise.resolve();
    await Promise.resolve();
  });

  expect(document.getElementById('authGate')).not.toBeNull();
  expect(document.getElementById('authInput')).not.toBeNull();
});
