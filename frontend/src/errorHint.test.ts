import { describe, expect, it } from 'vitest';
import { describeFailure, hintFor } from './errorHint';

/**
 * 失败引导的验收标准只有一条：**用户读完知道下一步做什么**。
 *
 * 所以这里不测"文案等于某个字符串"（那种断言改一个标点就红，守不住任何东西），
 * 测的是可判定的性质：四种类型各自给出不同建议、不可重试的类型不得暗示可以重发、
 * 未知 code 不能把页面变成空白。
 */
describe('hintFor', () => {
  it('四种类型给出互不相同的标题', () => {
    const titles = ['model_unavailable', 'timeout', 'bad_request', 'internal'].map(
      (code) => hintFor(code).title,
    );
    expect(new Set(titles).size).toBe(4);
  });

  it('模型不可用时不能引导用户重发', () => {
    const hint = hintFor('model_unavailable');
    expect(hint.retryable).toBe(false);
    // 用户要能从这句话里知道"得先有人把模型弄起来"
    expect(hint.hint).toMatch(/Ollama|配置/);
  });

  it('超时可以重试，并提示缩小范围', () => {
    const hint = hintFor('timeout');
    expect(hint.retryable).toBe(true);
    expect(hint.hint).toMatch(/缩小|少给/);
  });

  it('参数错误提示改了再发，而不是原样重发', () => {
    const hint = hintFor('bad_request');
    expect(hint.retryable).toBe(false);
    expect(hint.hint).toMatch(/改/);
  });

  it('认不出的 code 退回 internal，而不是不给文案', () => {
    // 服务端先上线了新类型、浏览器还缓存着旧页面时，走的就是这条路
    const unknown = hintFor('model_exploded');
    expect(unknown.title).toBe(hintFor('internal').title);
    expect(hintFor(undefined).title).toBe(hintFor('internal').title);
  });

  it('每条建议都不为空且不超长，避免挤爆状态栏', () => {
    for (const code of ['model_unavailable', 'timeout', 'bad_request', 'internal']) {
      const hint = hintFor(code);
      expect(hint.title.length).toBeGreaterThan(0);
      expect(hint.title.length).toBeLessThanOrEqual(12);
      expect(hint.hint.length).toBeGreaterThan(10);
      expect(hint.hint.length).toBeLessThanOrEqual(80);
    }
  });
});

describe('describeFailure', () => {
  it('带上请求 ID，用户报障时能直接给运维', () => {
    const text = describeFailure('timeout', 'a1b2c3d4');
    expect(text).toContain('a1b2c3d4');
    expect(text).toContain(hintFor('timeout').title);
  });

  it('没有请求 ID 时不留下空括号', () => {
    const text = describeFailure('internal');
    // internal 的正文里本来就有"请求 ID"四个字，所以判据是"没有拼出一个空的 ID"
    expect(text).not.toContain('（请求 ID');
    expect(text).not.toContain('（）');
    expect(text).not.toContain('undefined');
    expect(text).not.toContain('null');
  });
});
