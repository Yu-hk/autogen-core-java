import type { RefObject } from 'react';
import type { ThreadItem } from '../conversation';
import { shorten } from '../conversation';

const USER_COLOR = '#94a3b8';

interface Stats {
  round: number;
  messages: number;
  deltas: number;
  toolCalls: number;
}

interface Props {
  items: ThreadItem[];
  stats: Stats;
  phase: string;
  stopReason: string;
  threadRef: RefObject<HTMLDivElement>;
  onScroll: () => void;
}

function renderItem(item: ThreadItem) {
  if (item.type === 'divider') {
    return (
      <div className="divider" key={item.id}>
        {item.text}
      </div>
    );
  }
  if (item.type === 'user') {
    return (
      <div className="msg user" key={item.id}>
        <div className="avatar" style={{ background: USER_COLOR }}>
          U
        </div>
        <div className="msg-main">
          <div className="msg-head">
            <span className="msg-name">user</span>
            <span className="msg-role">任务</span>
          </div>
          <div className="bubble">{item.text}</div>
        </div>
      </div>
    );
  }
  return (
    <div className="msg" key={item.id}>
      <div className="avatar" style={{ background: item.color }}>
        {item.agent.slice(0, 1).toUpperCase()}
      </div>
      <div className="msg-main">
        <div className="msg-head">
          <span className="msg-name">{item.agent}</span>
          <span className="msg-role">{item.role}</span>
        </div>
        {item.bubbles.map((b) =>
          b.kind === 'text' ? (
            <div
              key={b.id}
              className={'bubble' + (b.streaming ? ' streaming' : '')}
            >
              {b.text}
            </div>
          ) : (
            <div
              key={b.id}
              className={'tool-line' + (b.toolKind === 'result' ? ' done' : '')}
            >
              <span className="k">{b.toolKind === 'request' ? '→ 调用' : '← 返回'}</span>
              <span className="v" title={b.text}>
                {shorten(b.text, 120)}
              </span>
            </div>
          ),
        )}
      </div>
    </div>
  );
}

export default function Conversation({
  items,
  stats,
  phase,
  stopReason,
  threadRef,
  onScroll,
}: Props) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>对话</h2>
        <div className="spacer" />
        <span className="status-text" id="phase">
          {phase}
        </span>
      </div>

      <div
        className="thread"
        id="thread"
        ref={threadRef}
        onScroll={onScroll}
      >
        {items.length === 0 ? (
          <div className="placeholder" id="placeholder">
            <div className="big">问一句就行，剩下的交给系统</div>
            <div>
              要不要查地图、用几个角色、用几个模型，都由问题本身决定，右侧时间线会写明判断依据
            </div>
          </div>
        ) : (
          items.map(renderItem)
        )}
      </div>

      <div className="composer">
        <div className="stat">
          <b id="statRound">{stats.round}</b>
          <span>轮次</span>
        </div>
        <div className="stat">
          <b id="statMsg">{stats.messages}</b>
          <span>消息</span>
        </div>
        <div className="stat">
          <b id="statDelta">{stats.deltas}</b>
          <span>增量片</span>
        </div>
        <div className="stat">
          <b id="statTool">{stats.toolCalls}</b>
          <span>工具</span>
        </div>
        <div className="stop-notice" id="stopReason">
          {stopReason}
        </div>
      </div>
    </section>
  );
}
