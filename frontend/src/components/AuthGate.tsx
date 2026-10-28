import { useState } from 'react';
import { HttpError, fetchHealth, setToken } from '../api';

/**
 * 访问口令闸门。
 *
 * <p>服务端一旦配置了 {@code server.auth-token}，所有 {@code /api/*} 都会返回 401。
 * 页面先照常加载（静态资源不拦），拿到 401 后再显示这一层——这样"没口令"和
 * "服务没起来"是两种不同的提示，不会都显示成"连不上"。</p>
 */
export default function AuthGate({ onAuthed }: { onAuthed: () => void }) {
  const [value, setValue] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const token = value.trim();
    if (!token || busy) return;
    setBusy(true);
    setError('');
    setToken(token);
    try {
      await fetchHealth();
      onAuthed();
    } catch (err) {
      setToken('');
      if (err instanceof HttpError && err.status === 401) {
        setError('口令不对。找部署的人要 SERVER_AUTH_TOKEN 那个值。');
      } else {
        setError('连不上服务：' + (err instanceof Error ? err.message : String(err)));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-mask" id="authGate">
      <form className="auth-card" onSubmit={submit}>
        <h2>需要访问口令</h2>
        <p className="auth-hint">
          这台服务开启了访问控制。口令由部署者设置（环境变量
          <code>SERVER_AUTH_TOKEN</code>），输入一次后会记在本浏览器里。
        </p>
        <input
          id="authInput"
          type="password"
          autoFocus
          autoComplete="current-password"
          placeholder="粘贴访问口令"
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
        {error && <div className="auth-error">{error}</div>}
        <button id="authGo" type="submit" disabled={busy || !value.trim()}>
          {busy ? '验证中…' : '进入'}
        </button>
      </form>
    </div>
  );
}
