import { useEffect, useState } from 'react';
import { HttpError, fetchFsList } from '../api';
import type { FsEntry } from '../types';

interface Props {
  open: boolean;
  onClose: () => void;
  onPick: (path: string) => void;
}

/**
 * 工作目录选择弹层。目录列表由 /api/fs/list 代劳（浏览器读不到本地路径），
 * 选中的路径由父组件存进 localStorage，刷新不用重选。
 * 每行带「选它」：直接把这一行定为工作目录，不必点进去再确认。
 */
export default function WorkspacePicker({ open, onClose, onPick }: Props) {
  const [path, setPath] = useState('');
  const [parent, setParent] = useState('');
  const [entries, setEntries] = useState<FsEntry[]>([]);
  const [warn, setWarn] = useState('');
  const [error, setError] = useState('');
  const [input, setInput] = useState('');
  const [useDisabled, setUseDisabled] = useState(true);

  async function load(p: string) {
    setError('');
    try {
      const data = await fetchFsList(p || undefined);
      if (data.error) {
        setError(data.error);
        setEntries([]);
        setUseDisabled(true);
        return;
      }
      setParent(data.parent || '');
      setPath(data.path || '');
      setInput(data.path || '');
      setEntries(data.entries || []);
      const usable = !!data.path && !!data.writable;
      setUseDisabled(!usable);
      if (data.blockReason) setWarn(data.blockReason);
      else if (!data.path) setWarn('先点进一个目录，再确认选它');
      else setWarn('选中后模型只在这个目录里读写');
    } catch (e) {
      // 403/401 是服务端给的具体原因（不在白名单 / 没口令），原样显示；
      // 只有网络层失败才提示"页签比服务旧"
      if (e instanceof HttpError && (e.status === 403 || e.status === 401)) {
        setError(e.message);
        setEntries([]);
        setUseDisabled(true);
        return;
      }
      const msg = e instanceof Error ? e.message : String(e);
      setError(
        '连不上后端服务（' +
          msg +
          '）。多半是这个页签比服务旧：服务重启或换端口后，请刷新页面或改用 ' +
          location.origin +
          ' 再打开。',
      );
      setEntries([]);
      setUseDisabled(true);
    }
  }

  // 打开时初次加载起点列表
  useEffect(() => {
    if (open) load(path);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  if (!open) return null;

  function goUp() {
    if (parent) load(parent);
  }
  function goTo() {
    const v = input.trim();
    if (v) load(v);
  }
  function pick(item: FsEntry) {
    if (item.blockReason) return;
    onPick(item.path);
    onClose();
  }
  function useCurrent() {
    if (useDisabled || !path) return;
    onPick(path);
    onClose();
  }

  const warnOk = !!warn && warn.includes('选中后');
  const footWarn = error || warn;

  return (
    <div className="ws-modal" id="wsModal">
      <div className="ws-card">
        <div className="ws-head">
          <strong>选择工作目录</strong>
          <span style={{ fontSize: 11, color: 'var(--faint)' }}>
            模型会在这个目录里读、改、编译
          </span>
          <button className="ws-x" id="wsClose" onClick={onClose} title="关闭">
            ×
          </button>
        </div>
        <div className="ws-bar">
          <button id="wsUp" onClick={goUp} title="上一级">
            ↑
          </button>
          <input
            id="wsInput"
            value={input}
            placeholder="直接输入路径，例如 D:/workspace/my-project"
            spellCheck={false}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                goTo();
              }
            }}
          />
          <button id="wsGo" onClick={goTo}>
            前往
          </button>
        </div>
        <div className="ws-cur" id="wsCur">
          {path || '选一个起点，点目录进入子目录'}
        </div>
        <div className="ws-list" id="wsList">
          {error ? null : entries.length === 0 ? (
            <div className="ws-empty">这个目录下没有子目录</div>
          ) : (
            entries.map((item) => (
              <div
                key={item.path}
                className={'ws-item' + (item.blockReason ? ' blocked' : '')}
                title={item.blockReason || item.path}
                onClick={() => load(item.path)}
              >
                <span className="ico">
                  {item.note === '盘符' || item.note === '根目录' ? '▣' : '▸'}
                </span>
                <span className="n">{item.name}</span>
                {item.note ? <span className="tag">{item.note}</span> : null}
                {item.git ? <span className="tag git">git</span> : null}
                {item.maven ? <span className="tag mvn">maven</span> : null}
                {!item.blockReason ? (
                  <button
                    className="ws-pick"
                    title={'把 ' + item.path + ' 设为工作目录'}
                    onClick={(e) => {
                      e.stopPropagation();
                      pick(item);
                    }}
                  >
                    选它
                  </button>
                ) : null}
              </div>
            ))
          )}
        </div>
        <div className="ws-foot">
          <span
            className={'ws-warn' + (warnOk ? ' ok' : '')}
            id="wsWarn"
          >
            {footWarn}
          </span>
          <button id="wsUse" disabled={useDisabled || !!error} onClick={useCurrent}>
            用这个目录
          </button>
        </div>
      </div>
    </div>
  );
}
