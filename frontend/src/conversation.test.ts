import { describe, expect, it } from 'vitest';
import { shorten, uid } from './conversation';

describe('uid', () => {
  it('连续生成不会撞', () => {
    // 时间戳是毫秒级的，同一毫秒内连续生成很常见——只靠 Date.now 会撞，
    // 撞了之后 React 的 key 重复，列表渲染就会串行
    const ids = new Set<string>();
    for (let i = 0; i < 5000; i++) ids.add(uid());
    expect(ids.size).toBe(5000);
  });

  it('带前缀，且能一眼看出是谁的 id', () => {
    expect(uid('bubble')).toMatch(/^bubble-/);
    expect(uid()).toMatch(/^id-/);
  });
});

describe('shorten', () => {
  it('不超长就原样返回', () => {
    expect(shorten('短文本', 10)).toBe('短文本');
    expect(shorten('正好十个字符啊啊啊啊', 10)).toBe('正好十个字符啊啊啊啊');
  });

  it('超长时截断并加省略号', () => {
    expect(shorten('一二三四五六七八九十十一', 10)).toBe('一二三四五六七八九十…');
  });

  it('空值不炸', () => {
    // 服务端返回的字段可能缺，直接 .length 会抛 TypeError 把整个渲染打断
    expect(shorten('', 5)).toBe('');
    expect(shorten(undefined as unknown as string, 5)).toBe('');
  });

  it('max 为 0 时只留省略号', () => {
    expect(shorten('abc', 0)).toBe('…');
  });
});
