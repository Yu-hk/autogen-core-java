// 对话线程的数据模型（与后端事件流一一对应），以及少量纯函数工具。

export interface TextBubble {
  id: string;
  kind: 'text';
  text: string;
  streaming: boolean;
}

export interface ToolBubble {
  id: string;
  kind: 'tool';
  toolKind: 'request' | 'result';
  text: string;
}

export type Bubble = TextBubble | ToolBubble;

export interface AgentItem {
  id: string;
  type: 'agent';
  agent: string;
  role: string;
  color: string;
  bubbles: Bubble[];
}

export type ThreadItem =
  | { id: string; type: 'divider'; text: string }
  | { id: string; type: 'user'; text: string }
  | AgentItem;

export interface TimelineItem {
  id: string;
  cls: string;
  text: string;
  time: string;
}

let _seq = 0;
export function uid(prefix = 'id'): string {
  _seq += 1;
  return `${prefix}-${Date.now().toString(36)}-${_seq}`;
}

export function shorten(text: string, max: number): string {
  const s = text || '';
  return s.length > max ? s.slice(0, max) + '…' : s;
}
