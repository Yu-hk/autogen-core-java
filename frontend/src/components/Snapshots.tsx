import type { Snapshot } from '../types';
import { shorten } from '../conversation';

interface Props {
  snapshots: Snapshot[];
  running: boolean;
  onResume: (file: string) => void;
}

export default function Snapshots({ snapshots, running, onResume }: Props) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>会话快照</h2>
      </div>
      <div className="panel-body" id="snapshots">
        {snapshots.length === 0 ? (
          <div className="empty">暂无快照，跑完一次会自动存档</div>
        ) : (
          snapshots.slice(0, 4).map((s) => (
            <div className="snap" key={s.file}>
              <div className="info">
                <div className="s">
                  轮次 {s.round} · {s.messageCount} 条消息
                </div>
                <div className="f" title={s.stopReason}>
                  {shorten(s.stopReason, 26)}
                </div>
              </div>
              <button disabled={running} onClick={() => onResume(s.file)}>
                续跑
              </button>
            </div>
          ))
        )}
      </div>
    </section>
  );
}
