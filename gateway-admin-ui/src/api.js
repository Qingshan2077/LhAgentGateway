const API_BASE = import.meta.env.VITE_ADMIN_API_BASE ?? ''

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  })
  if (!response.ok) {
    const message = await response.text()
    throw new Error(message || `HTTP ${response.status}`)
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') return null
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

function rangeQuery(days = 7) {
  const endTime = new Date()
  const startTime = new Date(endTime.getTime() - days * 86400000)
  return new URLSearchParams({ startTime: startTime.toISOString(), endTime: endTime.toISOString() })
}

export const api = {
  providers: () => request('/api/admin/providers'),
  saveProvider: (provider) => request(`/api/admin/providers/${encodeURIComponent(provider.name)}`, {
    method: 'PUT', body: JSON.stringify(provider),
  }),
  setProviderEnabled: (name, enabled) => request(
    `/api/admin/providers/${encodeURIComponent(name)}/${enabled ? 'enable' : 'disable'}`,
    { method: 'POST' },
  ),
  deleteProvider: (name) => request(`/api/admin/providers/${encodeURIComponent(name)}`, { method: 'DELETE' }),
  logs: (days = 7) => request(`/api/admin/logs?${rangeQuery(days)}&page=1&size=50`),
  costSummary: (days = 7) => request(`/api/admin/logs/cost-summary?${rangeQuery(days)}`),
  providerStats: (days = 7) => request(`/api/admin/logs/by-provider?${rangeQuery(days)}`),
}
