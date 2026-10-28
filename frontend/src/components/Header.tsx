import type { Health } from '../types';

interface Props {
  health: Health | null;
  modelReady: boolean;
}

export default function Header({ health, modelReady }: Props) {
  const twoStage = health?.summarizerModel
    ? `${health.model} → ${health.summarizerModel}`
    : health?.model;
  const dotClass = !health
    ? ''
    : health.ready
      ? 'ok'
      : 'bad';
  return (
    <header>
      <div className="brand">
        <div className="logo">A</div>
        <div>
          <h1>autogen-core-java</h1>
          <div className="sub">多智能体控制台 · 事件流来自运行时本身</div>
        </div>
      </div>
      <div className="spacer" />
      <span className={'dot ' + dotClass} id="healthDot" />
      <span className="status-text" id="healthText">
        {!health ? '检测模型…' : health.ready ? `${health.model} 就绪` : health.detail}
      </span>
      <span className="model-chip" id="modelChip" title={health?.baseUrl}>
        {twoStage || '—'}
      </span>
    </header>
  );
}
