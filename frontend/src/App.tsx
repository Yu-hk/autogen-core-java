import { useCallback, useEffect, useRef, useState } from 'react';
import Header from './components/Header';
import Sidebar from './components/Sidebar';
import Conversation from './components/Conversation';
import Timeline from './components/Timeline';
import Snapshots from './components/Snapshots';
import WorkspacePicker from './components/WorkspacePicker';
import AuthGate from './components/AuthGate';
import {
  HttpError,
  fetchConversation,
  fetchHealth,
  fetchScenarios,
  fetchSnapshots,
  openStream,
  resumeRun,
  startRun,
  stopRun,
} from './api';
import type { Health, Scenario, ScenarioMember, Snapshot, StreamEvent } from './types';
import { hintFor } from './errorHint';
import {
  AgentItem,
  Bubble,
  TextBubble,
  ThreadItem,
  TimelineItem,
  uid,
} from './conversation';

const PALETTE = ['#2563eb', '#7c3aed', '#059669', '#d97706', '#0891b2'];
const TASK_HINT = '用几句话把要求说清楚就行，不需要选场景。';

/**
 * 当前对话 id 存在 localStorage 里。
 *
 * <p>会话本身早就落到服务端磁盘了（重启不丢），但页面刷新会丢掉 React state 里的 id，
 * 于是"服务端还留着、页面却说这是新对话"。把 id 存本地，刷新之后能把那段对话捞回来——
 * 落库要有人用才算数。</p>
 */
const CONV_KEY = 'conv.id';

const SAMPLES = [
  '橘子洲的经纬度是多少',
  '规划长沙一日游，要景点坐标、附近餐馆、公交时长票价',
  '百万级订单导出方案该怎么设计',
  '用 Java 写一个线程安全的 LRU 缓存，写单测并编译运行到通过',
  '在沙箱数据库里建一张员工表，插几条数据，查出每个部门的平均工资',
  '怎么给 Git 提交改作者信息',
];

interface Stats {
  round: number;
  messages: number;
  deltas: number;
  toolCalls: number;
  retries: number;
}

export default function App() {
  const [needAuth, setNeedAuth] = useState(false);
  const [health, setHealth] = useState<Health | null>(null);
  const [modelReady, setModelReady] = useState(false);
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [snapshots, setSnapshots] = useState<Snapshot[]>([]);

  const [task, setTask] = useState('');
  const [taskHint] = useState(TASK_HINT);
  const [workspace, setWorkspace] = useState<string | null>(
    () => localStorage.getItem('ws.path') || null,
  );
  const [wsOpen, setWsOpen] = useState(false);

  const [running, setRunning] = useState(false);
  const [phase, setPhase] = useState('待运行');
  const [stopReason, setStopReason] = useState('');
  const [newChatVisible, setNewChatVisible] = useState(false);

  const [samples] = useState(SAMPLES);
  const [members, setMembers] = useState<ScenarioMember[]>([]);
  const [memberLabel, setMemberLabel] = useState('本次由谁回答');
  const [colorMap, setColorMap] = useState<Record<string, string>>({});
  const [activeAgent, setActiveAgent] = useState<string | null>(null);

  const [auto, setAuto] = useState(true);
  const [selectedScenario, setSelectedScenario] = useState<string | null>(null);
  const [maxRounds, setMaxRounds] = useState(4);

  const [items, setItems] = useState<ThreadItem[]>([]);
  const [timeline, setTimeline] = useState<TimelineItem[]>([]);
  const [stats, setStats] = useState<Stats>({
    round: 0,
    messages: 0,
    deltas: 0,
    toolCalls: 0,
    retries: 0,
  });

  // ---- refs（事件处理器里读最新值，避免闭包过期）----
  const currentBlockRef = useRef<Map<string, string>>(new Map());
  const colorRef = useRef<Record<string, string>>({});
  const roleRef = useRef<Record<string, string>>({});
  const startedAtRef = useRef(0);
  const sourceCloseRef = useRef<(() => void) | null>(null);
  const conversationIdRef = useRef<string | null>(null);
  const runningRef = useRef(false);
  const pinnedRef = useRef(true);
  const threadRef = useRef<HTMLDivElement>(null);

  /**
   * 等待心跳：模型调用期间没有 SSE 事件，页面上就只剩最后一条工具返回。
   * 内网纯 CPU 跑 7B 时单次调用要几分钟（实测 5–6 分钟），不给秒数用户只会以为卡死、
   * 然后重复点运行或直接关掉页面。
   */
  const [waitedSec, setWaitedSec] = useState(0);
  const lastEventRef = useRef(Date.now());
  /** 已经就"正在重连"提示过用户；避免每次重试都往时间线上刷一行。 */
  const reconnectingRef = useRef(false);
  const sessionIdRef = useRef<string | null>(null);

  const setRunningBoth = (v: boolean) => {
    runningRef.current = v;
    setRunning(v);
  };

  // ---------- 初始化 ----------
  useEffect(() => {
    if (needAuth) return; // 还没拿到口令，先别急着打接口
    void loadHealth();
    void loadScenarios();
    void loadSnapshots();
    void restoreConversation();
  }, [needAuth]);

  async function loadHealth() {
    try {
      const data = await fetchHealth();
      setHealth(data);
      setModelReady(Boolean(data.ready));
      if (data.ready) setPhase('待运行');
      else setPhase(data.detail);
    } catch (err) {
      if (err instanceof HttpError && err.status === 401) {
        setNeedAuth(true);
        return;
      }
      setHealth(null);
      setModelReady(false);
      setPhase('服务不可用');
    }
  }

  async function loadScenarios() {
    try {
      setScenarios(await fetchScenarios());
    } catch (err) {
      if (err instanceof HttpError && err.status === 401) setNeedAuth(true);
    }
  }

  async function loadSnapshots() {
    try {
      setSnapshots(await fetchSnapshots());
    } catch (err) {
      if (err instanceof HttpError && err.status === 401) setNeedAuth(true);
    }
  }

  /**
   * 刷新页面后把上次那段对话画回来。
   *
   * <p>没有这一步，会话落库只解决了"服务端记得"，用户看到的仍是一张白纸。
   * 取不到（过期被清、服务端换了部署）就安静地丢掉本地 id，当新对话开始——
   * 这里不该弹错误：用户没做错任何事，只是上次聊得太久了。</p>
   */
  async function restoreConversation(explicitId?: string) {
    const id = explicitId || localStorage.getItem(CONV_KEY);
    if (!id) return;
    try {
      const detail = await fetchConversation(id);
      const history = detail.history || [];
      if (!history.length) {
        localStorage.removeItem(CONV_KEY);
        return;
      }
      conversationIdRef.current = id;
      setNewChatVisible(true);
      clearThread();
      pushDivider(
        '已恢复上次对话 · ' +
          history.length +
          ' 条消息' +
          (detail.scenario ? ' · ' + detail.scenario : ''),
      );
      history.forEach((m) =>
        m.source === 'user'
          ? pushUser(m.text)
          : pushAgentFull(
              m.source,
              roleRef.current[m.source] || '',
              colorRef.current[m.source] || '#475569',
              m.text,
            ),
      );
      updateStats({ messages: history.length, round: detail.turns });
      setPhase('已恢复上次对话，可直接追问');
    } catch (err) {
      if (err instanceof HttpError && err.status === 401) {
        setNeedAuth(true);
        return;
      }
      localStorage.removeItem(CONV_KEY);
    }
  }

  /** 记下当前对话 id：内存 + localStorage 双写，页面刷新靠后者。 */
  function rememberConversation(id: string) {
    conversationIdRef.current = id;
    if (id) localStorage.setItem(CONV_KEY, id);
    else localStorage.removeItem(CONV_KEY);
  }

  // ---------- 工作目录 ----------
  function handlePickWorkspace(path: string) {
    setWorkspace(path);
    localStorage.setItem('ws.path', path);
  }

  // ---------- 场景选择 ----------
  function toggleScenario(id: string) {
    if (running) return;
    const s = scenarios.find((x) => x.id === id);
    if (!s) return;
    if (!auto && selectedScenario === id) {
      setAuto(true);
      setSelectedScenario(null);
      paintAuto();
      return;
    }
    setAuto(false);
    setSelectedScenario(id);
    setTask(s.task);
    setMaxRounds(s.maxRounds);
    paintMembers(s);
  }

  function paintAuto() {
    setMemberLabel('本次由谁回答');
    setMembers([]);
    setColorMap({});
    setActiveAgent(null);
  }

  function paintMembers(s: Scenario) {
    const cm: Record<string, string> = {};
    const rm: Record<string, string> = {};
    s.members.forEach((m, i) => {
      cm[m.key] = PALETTE[i % PALETTE.length];
      rm[m.key] = m.role;
    });
    colorRef.current = cm;
    roleRef.current = rm;
    setColorMap(cm);
    setMemberLabel('本次由谁回答 · ' + s.title);
    setMembers(s.members);
  }

  // ---------- 对话线程操作 ----------
  function ensureAgent(prev: ThreadItem[], agent: string): { items: ThreadItem[]; id: string } {
    const refId = currentBlockRef.current.get(agent);
    if (refId) {
      const ex = prev.find((it) => it.id === refId && it.type === 'agent');
      if (ex) return { items: prev, id: refId };
    }
    const id = uid('agent');
    const item: AgentItem = {
      id,
      type: 'agent',
      agent,
      role: roleRef.current[agent] || '',
      color: colorRef.current[agent] || '#475569',
      bubbles: [],
    };
    currentBlockRef.current.set(agent, id);
    return { items: [...prev, item], id };
  }

  const appendDelta = useCallback((agent: string, text: string) => {
    setItems((prev) => {
      const { items, id } = ensureAgent(prev, agent);
      const idx = items.findIndex((it) => it.id === id);
      const a = items[idx] as AgentItem;
      const bubbles = a.bubbles.slice();
      const last = bubbles[bubbles.length - 1];
      if (last && last.kind === 'text' && last.streaming) {
        bubbles[bubbles.length - 1] = { ...(last as TextBubble), text: last.text + text };
      } else {
        bubbles.push({ id: uid('b'), kind: 'text', text, streaming: true });
      }
      const next = items.slice();
      next[idx] = { ...a, bubbles };
      return next;
    });
  }, []);

  const addTool = useCallback((agent: string, toolKind: 'request' | 'result', text: string) => {
    setItems((prev) => {
      const { items, id } = ensureAgent(prev, agent);
      const idx = items.findIndex((it) => it.id === id);
      const a = items[idx] as AgentItem;
      const bubbles = a.bubbles.slice() as Bubble[];
      if (
        bubbles.length &&
        bubbles[bubbles.length - 1].kind === 'text' &&
        (bubbles[bubbles.length - 1] as TextBubble).streaming
      ) {
        const last = bubbles[bubbles.length - 1] as TextBubble;
        bubbles[bubbles.length - 1] = { ...last, streaming: false };
      }
      bubbles.push({ id: uid('b'), kind: 'tool', toolKind, text });
      const next = items.slice();
      next[idx] = { ...a, bubbles };
      return next;
    });
  }, []);

  const closeAgent = useCallback((agent: string) => {
    setItems((prev) => {
      const refId = currentBlockRef.current.get(agent);
      currentBlockRef.current.delete(agent);
      if (!refId) return prev;
      const idx = prev.findIndex((it) => it.id === refId);
      if (idx === -1) return prev;
      const a = prev[idx] as AgentItem;
      const bubbles = a.bubbles.slice() as Bubble[];
      if (
        bubbles.length &&
        bubbles[bubbles.length - 1].kind === 'text' &&
        (bubbles[bubbles.length - 1] as TextBubble).streaming
      ) {
        const last = bubbles[bubbles.length - 1] as TextBubble;
        bubbles[bubbles.length - 1] = { ...last, streaming: false };
      }
      const next = prev.slice();
      next[idx] = { ...a, bubbles };
      return next;
    });
  }, []);

  function pushUser(text: string) {
    setItems((prev) => [...prev, { id: uid('u'), type: 'user', text }]);
  }
  function pushAgentFull(agent: string, role: string, color: string, text: string) {
    const item: AgentItem = {
      id: uid('agent'),
      type: 'agent',
      agent,
      role,
      color,
      bubbles: [{ id: uid('b'), kind: 'text', text, streaming: false }],
    };
    setItems((prev) => [...prev, item]);
  }
  function clearThread() {
    currentBlockRef.current.clear();
    setItems([]);
  }
  function pushDivider(text: string) {
    setItems((prev) => [...prev, { id: uid('d'), type: 'divider', text }]);
  }

  function addTimeline(cls: string, text: string) {
    const now = startedAtRef.current ? (Date.now() - startedAtRef.current) / 1000 : 0;
    setTimeline((prev) => [
      ...prev,
      { id: uid('tl'), cls, text, time: now.toFixed(1) + 's' },
    ]);
  }

  function updateStats(patch: Partial<Stats>) {
    setStats((prev) => ({ ...prev, ...patch }));
  }

  function setRunningUI(on: boolean) {
    setRunningBoth(on);
    setRunning(on); // 保证 run/stop 按钮状态
    if (on) {
      lastEventRef.current = Date.now();
      setWaitedSec(0);
      setPhase('运行中');
    }
    document.querySelectorAll('.snap button').forEach((b) => {
      (b as HTMLButtonElement).disabled = on;
    });
  }

  /**
   * 出错收尾。
   *
   * @param detail 技术原文，进分隔线（排查用）
   * @param title  状态栏短标题，说明是哪种失败
   * @param next   下一步建议；没有时退回原文
   */
  function showError(detail: string, title = '出错了', next?: string) {
    setPhase(title);
    // 顶部状态栏是最先被看到的位置，放"该做什么"比放"哪里错了"更有用
    setStopReason(next || detail);
    pushDivider('错误：' + detail);
    setRunningBoth(false);
    document.querySelectorAll('.snap button').forEach((b) => {
      (b as HTMLButtonElement).disabled = false;
    });
  }

  function scrollDown() {
    if (pinnedRef.current && threadRef.current) {
      threadRef.current.scrollTop = threadRef.current.scrollHeight;
    }
  }
  useEffect(() => {
    scrollDown();
  }, [items]);

  useEffect(() => {
    if (!running) {
      setWaitedSec(0);
      return;
    }
    const timer = window.setInterval(() => {
      const sec = Math.floor((Date.now() - lastEventRef.current) / 1000);
      const next = sec >= 5 ? sec : 0;
      setWaitedSec((prev) => (prev === next ? prev : next));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [running]);

  function resetCounters() {
    currentBlockRef.current.clear();
    setStats({ round: 0, messages: 0, deltas: 0, toolCalls: 0, retries: 0 });
    startedAtRef.current = Date.now();
    setActiveAgent(null);
  }
  function resetStage() {
    clearThread();
    setTimeline([]);
    setStopReason('');
    pinnedRef.current = true;
    resetCounters();
  }

  // ---------- 运行 ----------
  async function handleRun() {
    if (runningRef.current) return;
    const t = task.trim();
    if (!t) return;
    const followUp = !!conversationIdRef.current;
    if (followUp) {
      resetCounters();
      pushDivider('追问 · 接着上一轮');
    } else {
      resetStage();
      pushDivider(auto ? '发送 · 自动判断方式' : '开始运行 · ' + (selectedScenario || ''));
    }

    const params = {
      task: t,
      conversationId: conversationIdRef.current || undefined,
      workspace: workspace || undefined,
      scenario: auto ? 'auto' : selectedScenario || undefined,
      maxRounds: auto ? undefined : maxRounds,
    };
    try {
      const data = await startRun(params);
      if (data.error) {
        showError(data.error);
        return;
      }
      rememberConversation(data.conversationId);
      setNewChatVisible(true);
      setTask('');
      connect(data.sessionId);
    } catch (e) {
      // 401 = 口令失效（换了 token 或清过缓存），回到闸门重新输入；其余错误照常显示
      if (e instanceof HttpError && e.status === 401) {
        setNeedAuth(true);
        return;
      }
      showError(e instanceof Error ? e.message : String(e));
    }
  }

  async function handleResume(file: string) {
    if (runningRef.current) return;
    resetStage();
    const params = {
      file,
      scenario: auto ? 'general' : selectedScenario || 'general',
      maxRounds: auto ? undefined : maxRounds,
      workspace: workspace || undefined,
    };
    try {
      const data = await resumeRun(params);
      if (data.error) {
        showError(data.error);
        return;
      }
      rememberConversation(data.conversationId);
      setNewChatVisible(true);
      connect(data.sessionId);
    } catch (e) {
      // 401 = 口令失效（换了 token 或清过缓存），回到闸门重新输入；其余错误照常显示
      if (e instanceof HttpError && e.status === 401) {
        setNeedAuth(true);
        return;
      }
      showError(e instanceof Error ? e.message : String(e));
    }
  }

  /**
   * 停止按钮必须带 <b>sessionId</b>，不是 conversationId。
   *
   * <p>服务端的 {@code /api/stop} 找的是"这一次运行"，而 conversationId 是一整段对话。
   * 之前这里传的是后者，接口回 404、页面却显示"本轮结束后停止…"，看起来按了没反应。</p>
   */
  function handleStop() {
    const sessionId = sessionIdRef.current;
    if (!sessionId) return;
    void stopRun(sessionId);
    setPhase('本轮结束后停止…');
  }

  function newChat() {
    if (runningRef.current) return;
    rememberConversation('');
    sessionIdRef.current = null;
    currentBlockRef.current.clear();
    resetStage();
    setTask('');
    setMemberLabel('本次由谁回答');
    setMembers([]);
    setColorMap({});
    setActiveAgent(null);
    setNewChatVisible(false);
  }

  function connect(sessionId: string) {
    sessionIdRef.current = sessionId;
    setRunningBoth(true);
    setRunningUI(true);
    startedAtRef.current = Date.now();
    addTimeline('speak', '会话 ' + sessionId + ' 已创建');
    const close = openStream(
      sessionId,
      (e) => handleEvent(e),
      (state) => {
        if (state === 'open') {
          reconnectingRef.current = false;
          return;
        }
        if (state === 'reconnecting') {
          // 浏览器会自己带 Last-Event-ID 重连，服务端从断点补发。这里只要让用户知道
          // "刚才断了一下，正在补"，而不是把界面锁死——跑得好好的任务没必要因为一次抖动就作废
          if (!reconnectingRef.current) {
            reconnectingRef.current = true;
            addTimeline('speak', '与服务器的流中断，正在从断点续传');
          }
          setPhase('连接中断，正在重连…');
          return;
        }
        if (runningRef.current) {
          setRunningBoth(false);
          document.querySelectorAll('.snap button').forEach((b) => {
            (b as HTMLButtonElement).disabled = false;
          });
          setPhase('连接断开');
        }
      },
    );
    sourceCloseRef.current = close;
  }

  function finish() {
    if (sourceCloseRef.current) sourceCloseRef.current();
    sourceCloseRef.current = null;
    setRunningBoth(false);
    document.querySelectorAll('.snap button').forEach((b) => {
      (b as HTMLButtonElement).disabled = false;
    });
  }

  function handleEvent(data: StreamEvent) {
    lastEventRef.current = Date.now();
    setWaitedSec(0);
    switch (data.kind) {
      case 'status':
        setPhase(data.detail || '运行中');
        break;
      case 'route': {
        const s = scenarios.find((x) => x.id === data.scenario);
        if (s) {
          paintMembers(s);
        } else {
          setMemberLabel('本次由谁回答 · ' + (data.title || ''));
        }
        setPhase('方式：' + (data.title || ''));
        addTimeline(
          'route',
          '自动选择 ' + (data.title || '') + ' · ' + (data.reason || ''),
        );
        break;
      }
      case 'history': {
        if (data.append) {
          addTimeline('speak', '接着这段对话 · 已有 ' + (data.count || 0) + ' 条消息');
          updateStats({ messages: data.count || 0 });
          break;
        }
        clearThread();
        pushDivider(data.note || '恢复历史');
        (data.messages || []).forEach((m) =>
          m.source === 'user'
            ? pushUser(m.text)
            : pushAgentFull(
                m.source,
                roleRef.current[m.source] || '',
                colorRef.current[m.source] || '#475569',
                m.text,
              ),
        );
        updateStats({
          round: data.round || 0,
          messages: (data.messages || []).length,
        });
        addTimeline(
          'speak',
          '恢复 ' + (data.messages || []).length + ' 条历史，轮次 ' + (data.round || 0),
        );
        break;
      }
      case 'retry':
        updateStats({ retries: stats.retries + 1 });
        setPhase(
          '调用失败，' + Math.round(((data.delayMillis || 0) / 1000) * 10) / 10 + ' 秒后重试',
        );
        addTimeline(
          'retry',
          (data.agent || '') +
            ' 第 ' +
            (data.attempt || 0) +
            '/' +
            (data.maxRetries || 0) +
            ' 次重试 · ' +
            ((data.reason || '').slice(0, 60)),
        );
        break;
      case 'message':
        if (data.source === 'user') pushUser(data.text || '');
        else
          pushAgentFull(
            data.source || '',
            roleRef.current[data.source || ''] || '',
            colorRef.current[data.source || ''] || '#475569',
            data.text || '',
          );
        updateStats({ messages: stats.messages + 1 });
        break;
      case 'delta':
        appendDelta(data.agent || '', data.text || '');
        updateStats({ deltas: stats.deltas + 1 });
        break;
      case 'event':
        if (data.name === 'SpeakingStarted') {
          if (data.round && data.round !== stats.round) {
            updateStats({ round: data.round });
            addTimeline('speak', '进入第 ' + data.round + ' 轮');
          }
          setActiveAgent(data.agent || null);
          addTimeline(
            'speak',
            (data.agent || '') + ' 开始发言（round ' + (data.round || 0) + '）',
          );
        } else {
          if (data.agent) closeAgent(data.agent);
          setActiveAgent(null);
          addTimeline(
            'speak',
            (data.agent || '') + ' 发言结束 · ' + (data.length || 0) + ' 字',
          );
        }
        break;
      case 'tool':
        if (data.phase === 'request') {
          updateStats({ toolCalls: stats.toolCalls + 1 });
          addTool(data.agent || '', 'request', `${data.tool}(${data.arguments || ''})`);
          addTimeline('tool', (data.agent || '') + ' 请求工具 ' + (data.tool || ''));
        } else {
          addTool(
            data.agent || '',
            'result',
            `${data.result} · ${data.millis}ms`,
          );
          addTimeline('tool', '工具返回 ' + (data.result || '') + ' · ' + (data.millis || 0) + 'ms');
        }
        break;
      case 'done':
        updateStats({
          round: data.round || 0,
          messages: data.messages || 0,
          deltas: data.deltas || 0,
        });
        currentBlockRef.current.forEach((_v, agent) => closeAgent(agent));
        if (data.retries) {
          addTimeline('retry', '本轮共重试 ' + data.retries + ' 次');
        }
        setStopReason('停止：' + (data.stopReason || ''));
        setPhase('已完成');
        addTimeline('end', '结束 · ' + (data.stopReason || ''));
        finish();
        void loadSnapshots();
        break;
      case 'error': {
        // 分类先于文案：同样的原文配上不同的 code，用户该做的事完全不同
        const hint = hintFor(data.code);
        const request = data.requestId ? ' · 请求 ID ' + data.requestId : '';
        showError(
          (data.message || '未知错误') + request,
          hint.title,
          hint.hint + request,
        );
        addTimeline('err', hint.hint);
        finish();
        break;
      }
      case 'resync':
        // 断太久，服务端已经把那段事件滚出去了：补不了，重拉一次完整对话再往后跟
        addTimeline('speak', data.message || '事件缺口，重新拉取完整对话');
        void restoreConversation(conversationIdRef.current || undefined);
        break;
      case 'expired':
        showError(
          data.message || '这次运行的连接已失效，重新发起即可。',
          '连接已失效',
          '服务可能重启过。重新发一次即可，不用等它自己恢复。',
        );
        addTimeline('err', '连接已失效 · 服务可能重启过');
        finish();
        break;
      default:
        break;
    }
  }

  function onThreadScroll() {
    const el = threadRef.current;
    if (!el) return;
    pinnedRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  }

  if (needAuth) {
    return <AuthGate onAuthed={() => setNeedAuth(false)} />;
  }

  return (
    <>
      <Header health={health} modelReady={modelReady} />
      <main>
        <Sidebar
          task={task}
          setTask={setTask}
          taskHint={taskHint}
          workspace={workspace}
          onOpenWs={() => setWsOpen(true)}
          running={running}
          modelReady={modelReady}
          onRun={handleRun}
          onStop={handleStop}
          onNewChat={newChat}
          newChatVisible={newChatVisible}
          samples={samples}
          members={members}
          memberLabel={memberLabel}
          colorMap={colorMap}
          activeAgent={activeAgent}
          scenarios={scenarios}
          auto={auto}
          selectedScenario={selectedScenario}
          onToggleScenario={toggleScenario}
          maxRounds={maxRounds}
          setMaxRounds={setMaxRounds}
        />
        <Conversation
          items={items}
          stats={stats}
          phase={
            running && waitedSec >= 5
              ? phase + ' · 等待模型 ' + waitedSec + 's（首次或长上下文时较慢）'
              : phase
          }
          stopReason={stopReason}
          threadRef={threadRef}
          onScroll={onThreadScroll}
        />
        <aside className="right-col">
          <Timeline items={timeline} />
          <Snapshots snapshots={snapshots} running={running} onResume={handleResume} />
        </aside>
      </main>
      <WorkspacePicker
        open={wsOpen}
        onClose={() => setWsOpen(false)}
        onPick={handlePickWorkspace}
      />
    </>
  );
}
