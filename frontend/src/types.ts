// 后端 API 的类型定义，与 AgentWebServer 的返回结构一一对应。

export interface Health {
  ok: boolean;
  port: number;
  provider: string;
  model: string;
  baseUrl: string;
  ready: boolean;
  detail: string;
  configSource?: string;
  summarizerModel?: string;
  summarizerProvider?: string;
  summarizerDetail?: string;
  mapProvider?: string;
  mapName?: string;
  mapApplyUrl?: string;
  mapKeyField?: string;
  mapReady?: boolean;
  mapError?: string;
}

export interface ScenarioMember {
  key: string;
  role: string;
  tools: boolean;
  toolset: string;
  model: 'main' | 'summarizer';
}

export interface Scenario {
  id: string;
  title: string;
  description: string;
  task: string;
  stopWord: string;
  maxRounds: number;
  selector: boolean;
  members: ScenarioMember[];
}

export interface Snapshot {
  file: string;
  team: string;
  round: number;
  messageCount: number;
  stopReason: string;
  savedAt: string;
}

/** 会话摘要：列表用，不带消息正文。 */
export interface ConversationSummary {
  conversationId: string;
  scenario: string;
  turns: number;
  messages: number;
  lastActiveAt: number;
  cached?: boolean;
}

/** 会话详情：带整段消息，页面刷新后靠它把对话画回来。 */
export interface ConversationDetail extends ConversationSummary {
  history: { source: string; text: string }[];
}

export interface FsEntry {
  name: string;
  path: string;
  note?: string;
  git?: boolean;
  maven?: boolean;
  blockReason?: string;
}

export interface FsList {
  ok: boolean;
  path: string;
  parent: string;
  writable?: boolean;
  blockReason?: string;
  total?: number;
  truncated?: boolean;
  entries: FsEntry[];
  error?: string;
}

export interface RunResponse {
  sessionId: string;
  conversationId: string;
  followUp: boolean;
  workspace?: string;
  error?: string;
}

export type StreamEvent =
  | { kind: 'status'; detail?: string }
  | { kind: 'route'; scenario?: string; title?: string; reason?: string }
  | {
      kind: 'history';
      append?: boolean;
      count?: number;
      note?: string;
      messages?: { source: string; text: string }[];
      round?: number;
    }
  | {
      kind: 'retry';
      agent?: string;
      attempt?: number;
      maxRetries?: number;
      delayMillis?: number;
      reason?: string;
    }
  | { kind: 'message'; source?: string; text?: string }
  | { kind: 'delta'; agent?: string; text?: string }
  | {
      kind: 'event';
      name?: string; // SpeakingStarted | SpeakingEnded
      round?: number;
      length?: number;
      agent?: string;
    }
  | {
      kind: 'tool';
      phase?: 'request' | 'result';
      agent?: string;
      tool?: string;
      arguments?: string;
      result?: string;
      millis?: number;
    }
  | {
      kind: 'done';
      round?: number;
      messages?: number;
      deltas?: number;
      retries?: number;
      stopReason?: string;
    }
  | { kind: 'error'; message?: string };
