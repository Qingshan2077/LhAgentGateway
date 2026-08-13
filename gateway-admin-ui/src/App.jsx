import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from './api.js'

const emptyProvider = {
  name: '', displayName: '', baseUrl: '', apiKey: '', weight: 1,
  rateLimitRpm: 60, rateLimitTpm: 100000, enabled: true, routingEnabled: true,
}

const number = (value) => new Intl.NumberFormat('zh-CN').format(value ?? 0)
const money = (value) => `$${Number(value ?? 0).toFixed(4)}`

function App() {
  const [active, setActive] = useState('overview')
  const [providers, setProviders] = useState([])
  const [logs, setLogs] = useState([])
  const [summary, setSummary] = useState({})
  const [providerStats, setProviderStats] = useState([])
  const [editing, setEditing] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [nextProviders, nextLogs, nextSummary, nextStats] = await Promise.all([
        api.providers(), api.logs(), api.costSummary(), api.providerStats(),
      ])
      setProviders(nextProviders ?? [])
      setLogs(nextLogs ?? [])
      setSummary(nextSummary ?? {})
      setProviderStats(nextStats ?? [])
    } catch (cause) {
      setError(cause.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const healthyCount = useMemo(() => providers.filter((item) => item.enabled).length, [providers])

  async function toggleProvider(provider) {
    await api.setProviderEnabled(provider.name, !provider.enabled)
    await load()
  }

  async function removeProvider(provider) {
    if (!window.confirm(`确认删除 ${provider.displayName || provider.name}？`)) return
    await api.deleteProvider(provider.name)
    await load()
  }

  async function saveProvider(event) {
    event.preventDefault()
    await api.saveProvider(editing)
    setEditing(null)
    await load()
  }

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">LH</span><div><strong>Gateway</strong><small>CONTROL PLANE</small></div></div>
        <nav>
          {[
            ['overview', '总览', '◫'], ['providers', 'Provider', '◇'], ['logs', '调用日志', '≡'],
          ].map(([id, label, icon]) => (
            <button className={active === id ? 'active' : ''} key={id} onClick={() => setActive(id)}>
              <span>{icon}</span>{label}
            </button>
          ))}
        </nav>
        <div className="sidebar-status"><i />Gateway runtime sync<span>≤ 5 秒</span></div>
      </aside>

      <main>
        <header><div><p>LLM INFRASTRUCTURE</p><h1>{active === 'overview' ? '运行总览' : active === 'providers' ? 'Provider 配置' : '调用日志'}</h1></div>
          <button className="refresh" onClick={load} disabled={loading}>{loading ? '同步中…' : '刷新数据'}</button></header>
        {error && <div className="error">数据加载失败：{error}</div>}

        {active === 'overview' && <>
          <section className="metrics">
            <Metric label="近 7 天调用" value={number(summary.totalCalls)} note={`${number(summary.successCount)} 成功`} />
            <Metric label="Token 消耗" value={number(summary.totalTokens)} note="prompt + completion" />
            <Metric label="平均延迟" value={`${number(Math.round(summary.avgLatencyMs ?? 0))} ms`} note="全 Provider" />
            <Metric label="估算成本" value={money(summary.totalCost)} note="USD" />
          </section>
          <section className="grid-two">
            <div className="panel"><PanelTitle title="Provider 流量" meta={`${healthyCount}/${providers.length} 已启用`} />
              <div className="provider-bars">{providerStats.map((stat) => {
                const max = Math.max(...providerStats.map((item) => item.callCount || 0), 1)
                return <div className="bar-row" key={stat.provider}><div><strong>{stat.provider}</strong><span>{number(stat.callCount)} calls</span></div>
                  <div className="bar-track"><i style={{ width: `${Math.max(4, (stat.callCount / max) * 100)}%` }} /></div></div>
              })}{!providerStats.length && <Empty text="暂无调用统计" />}</div>
            </div>
            <div className="panel"><PanelTitle title="最近请求" meta="实时审计日志" /><LogList logs={logs.slice(0, 7)} compact /></div>
          </section>
        </>}

        {active === 'providers' && <section className="panel wide">
          <PanelTitle title="Provider 注册表" meta="修改后由网关周期热刷新" action={<button className="primary" onClick={() => setEditing({ ...emptyProvider })}>新增 Provider</button>} />
          <div className="provider-table table">
            <div className="tr th"><span>Provider</span><span>Endpoint</span><span>路由</span><span>权重</span><span>限额 RPM</span><span>操作</span></div>
            {providers.map((provider) => <div className="tr" key={provider.name}>
              <span className="provider-name"><i className={provider.enabled ? 'online' : ''} /><div><strong>{provider.displayName || provider.name}</strong><small>{provider.name}</small></div></span>
              <span className="endpoint" title={provider.baseUrl}>{provider.baseUrl}</span>
              <span><b className={`pill ${provider.enabled ? 'green' : ''}`}>{provider.enabled ? 'ENABLED' : 'DISABLED'}</b></span>
              <span>{provider.weight}</span><span>{number(provider.rateLimitRpm)}</span>
              <span className="actions"><button onClick={() => setEditing({ ...provider })}>编辑</button><button onClick={() => toggleProvider(provider)}>{provider.enabled ? '停用' : '启用'}</button><button className="danger" onClick={() => removeProvider(provider)}>删除</button></span>
            </div>)}
          </div>
        </section>}

        {active === 'logs' && <section className="panel wide"><PanelTitle title="请求审计" meta="最近 7 天 · 最多 50 条" /><LogList logs={logs} /></section>}
      </main>

      {editing && <div className="modal-backdrop"><form className="modal" onSubmit={saveProvider}>
        <PanelTitle title={editing.id ? '编辑 Provider' : '新增 Provider'} meta="配置会同步至运行中网关" />
        <div className="form-grid">
          <Field label="标识" value={editing.name} disabled={Boolean(editing.id)} onChange={(name) => setEditing({ ...editing, name })} />
          <Field label="显示名称" value={editing.displayName} onChange={(displayName) => setEditing({ ...editing, displayName })} />
          <Field label="Base URL" value={editing.baseUrl} wide onChange={(baseUrl) => setEditing({ ...editing, baseUrl })} />
          <Field label="API Key" value={editing.apiKey} type="password" wide onChange={(apiKey) => setEditing({ ...editing, apiKey })} />
          <Field label="路由权重" value={editing.weight} type="number" onChange={(weight) => setEditing({ ...editing, weight: Number(weight) })} />
          <Field label="RPM 限额" value={editing.rateLimitRpm} type="number" onChange={(rateLimitRpm) => setEditing({ ...editing, rateLimitRpm: Number(rateLimitRpm) })} />
          <Field label="TPM 限额" value={editing.rateLimitTpm} type="number" onChange={(rateLimitTpm) => setEditing({ ...editing, rateLimitTpm: Number(rateLimitTpm) })} />
          <label className="check"><input type="checkbox" checked={editing.enabled} onChange={(e) => setEditing({ ...editing, enabled: e.target.checked })} />启用 Provider</label>
          <label className="check"><input type="checkbox" checked={editing.routingEnabled} onChange={(e) => setEditing({ ...editing, routingEnabled: e.target.checked })} />允许主路由直转</label>
        </div>
        <div className="modal-actions"><button type="button" onClick={() => setEditing(null)}>取消</button><button className="primary" type="submit">保存配置</button></div>
      </form></div>}
    </div>
  )
}

function Metric({ label, value, note }) { return <article className="metric"><span>{label}</span><strong>{value}</strong><small>{note}</small></article> }
function PanelTitle({ title, meta, action }) { return <div className="panel-title"><div><h2>{title}</h2><p>{meta}</p></div>{action}</div> }
function Empty({ text }) { return <div className="empty">{text}</div> }
function Field({ label, value, onChange, type = 'text', wide, disabled }) { return <label className={wide ? 'wide' : ''}><span>{label}</span><input required value={value ?? ''} type={type} disabled={disabled} onChange={(e) => onChange(e.target.value)} /></label> }
function LogList({ logs, compact }) {
  if (!logs.length) return <Empty text="暂无调用日志" />
  return <div className={`log-list ${compact ? 'compact' : ''}`}>{logs.map((log) => <div className="log-row" key={log.id ?? log.requestId}>
    <span className={`status-dot ${log.status}`} /><span className="log-model"><strong>{log.model || 'unknown'}</strong><small>{log.provider}</small></span>
    {!compact && <span className="request-id">{log.requestId}</span>}<span>{number(log.totalTokens)} tok</span><span>{number(log.latencyMs)} ms</span><b className={`pill ${log.status === 'success' ? 'green' : 'red'}`}>{log.status}</b>
  </div>)}</div>
}

export default App
