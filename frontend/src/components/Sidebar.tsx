import type { Scenario, ScenarioMember } from '../types';

const PALETTE = ['#2563eb', '#7c3aed', '#059669', '#d97706', '#0891b2'];

function toolBadge(toolset: string): string {
  switch (toolset) {
    case 'travel':
      return '7 tools';
    case 'coding':
      return '11 tools';
    case 'readonly':
      return '只读 4 tools';
    case 'calculator':
      return '1 tool';
    default:
      return 'tools';
  }
}

interface Props {
  task: string;
  setTask: (v: string) => void;
  taskHint: string;
  workspace: string | null;
  onOpenWs: () => void;
  running: boolean;
  modelReady: boolean;
  onRun: () => void;
  onStop: () => void;
  onNewChat: () => void;
  newChatVisible: boolean;
  samples: string[];
  members: ScenarioMember[];
  memberLabel: string;
  colorMap: Record<string, string>;
  activeAgent: string | null;
  scenarios: Scenario[];
  auto: boolean;
  selectedScenario: string | null;
  onToggleScenario: (id: string) => void;
  maxRounds: number;
  setMaxRounds: (v: number) => void;
}

export default function Sidebar(props: Props) {
  const {
    task,
    setTask,
    taskHint,
    workspace,
    onOpenWs,
    running,
    modelReady,
    onRun,
    onStop,
    onNewChat,
    newChatVisible,
    samples,
    members,
    memberLabel,
    colorMap,
    activeAgent,
    scenarios,
    auto,
    selectedScenario,
    onToggleScenario,
    maxRounds,
    setMaxRounds,
  } = props;

  return (
    <aside className="panel">
      <div className="panel-head">
        <h2>提问</h2>
      </div>
      <div className="panel-body">
        <textarea
          className="task"
          id="taskInput"
          rows={5}
          spellCheck={false}
          placeholder={
            '直接说你想做什么，比如：\n帮我规划长沙一日游，橘子洲、岳麓山都要坐标\n百万级订单导出方案该怎么设计'
          }
          value={task}
          onChange={(e) => setTask(e.target.value)}
        />
        <div className="hint" id="taskHint">
          {taskHint}
        </div>

        <div className="wsrow">
          <span className="ws-label">工作目录</span>
          <button
            className={'ws-path' + (workspace ? ' custom' : '')}
            id="wsPath"
            title={
              workspace
                ? workspace + '（点击更换）'
                : '没选目录时无法读写本地文件；点这里选一个项目目录'
            }
            onClick={onOpenWs}
          >
            {workspace || '未指定（点这里选本机目录）'}
          </button>
        </div>

        <button
          className="primary"
          id="runBtn"
          disabled={running || !modelReady}
          onClick={onRun}
        >
          发送
        </button>
        <button className="ghost" id="stopBtn" disabled={!running} onClick={onStop}>
          停止（本轮结束后生效）
        </button>
        <button
          className="ghost"
          id="newChatBtn"
          hidden={!newChatVisible}
          onClick={onNewChat}
        >
          新对话（丢掉上文）
        </button>

        <div className="label">试试这些问法</div>
        <div id="samples">
          {samples.map((text) => (
            <button
              key={text}
              type="button"
              className="sample"
              onClick={() => {
                if (running) return;
                setTask(text);
              }}
            >
              {text}
            </button>
          ))}
        </div>

        <div className="label" id="memberLabel">
          {memberLabel}
        </div>
        <div id="members">
          {members.length === 0 ? (
            <div className="empty-mini">发送后按问题自动分配。</div>
          ) : (
            members.map((m) => (
              <div
                key={m.key}
                className={'member' + (m.key === activeAgent ? ' active' : '')}
                data-agent={m.key}
              >
                <div
                  className="avatar"
                  style={{ background: colorMap[m.key] || '#475569' }}
                >
                  {m.key.slice(0, 1).toUpperCase()}
                </div>
                <div className="member-meta">
                  <div className="n">{m.key}</div>
                  <div className="r">{m.role}</div>
                </div>
                {m.tools ? (
                  <span className="badge">{toolBadge(m.toolset)}</span>
                ) : null}
                {m.model === 'summarizer' ? (
                  <span className="badge alt">2nd model</span>
                ) : null}
              </div>
            ))
          )}
        </div>

        <details className="advanced" id="advanced">
          <summary>手动指定协作方式（演示用）</summary>
          <div id="scenes">
            {scenarios.map((s) => (
              <div
                key={s.id}
                className={
                  'scene' +
                  (!auto && selectedScenario === s.id ? ' on' : '')
                }
                data-id={s.id}
                onClick={() => onToggleScenario(s.id)}
              >
                <div className="t">{s.title}</div>
                <div className="d">{s.description}</div>
              </div>
            ))}
          </div>

          <div className="label">最大轮次</div>
          <div className="field">
            <label htmlFor="maxRounds">仅手动模式下生效</label>
            <input
              type="number"
              id="maxRounds"
              min={1}
              max={8}
              value={maxRounds}
              onChange={(e) => {
                const v = Math.max(1, Math.min(8, Number(e.target.value) || 4));
                setMaxRounds(v);
              }}
            />
          </div>
        </details>
      </div>
    </aside>
  );
}
