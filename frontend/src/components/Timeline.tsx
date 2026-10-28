import type { TimelineItem } from '../conversation';

interface Props {
  items: TimelineItem[];
}

export default function Timeline({ items }: Props) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>事件时间线</h2>
      </div>
      <div className="panel-body timeline" id="timeline">
        {items.length === 0 ? (
          <div className="empty">等待事件…</div>
        ) : (
          items.map((it) => (
            <div className={'tl-item ' + it.cls} key={it.id}>
              <span className="tl-time">{it.time}</span>
              <span className="tl-text">{it.text}</span>
            </div>
          ))
        )}
      </div>
    </section>
  );
}
