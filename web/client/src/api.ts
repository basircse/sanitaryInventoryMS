// Typed calls to the Spring Boot API. Money is in poisha, quantities in thousandths, times in epoch ms.

export interface Category { id: number; parentId: number | null; name: string; level: number }
export interface Supplier { id: number; name: string; phone: string | null; note: string | null }
export interface Item {
  id: number; name: string; sku: string; categoryId: number | null; supplierId: number | null; unit: string
  purchasePoisha: number; sellingPoisha: number; qtyMilli: number; thresholdMilli: number | null
  createdAt: number; updatedAt: number
}
export interface Movement { id: number; itemId: number; type: string; qtyDeltaMilli: number; note: string | null; at: number }
export interface SaleRow {
  id: number; itemId: number; itemName: string | null; unit: string | null; supplierId: number | null
  qtyMilli: number; unitPoisha: number; totalPoisha: number; costPoisha: number; soldAt: number
}
export interface Stats { totalItems: number; lowStock: number; outOfStock: number; stockValuePoisha: number }

export type LicenseState = 'NOT_ACTIVATED' | 'ACTIVE' | 'EXPIRED' | 'CLOCK_TAMPERED'
export interface LicenseStatus {
  state: LicenseState; deviceCode: string; serial: number | null; expiresAt: number | null
  daysLeft: number | null; backupMode: 'DAILY' | 'WEEKLY' | 'MONTHLY' | null; expiringSoon: boolean
}
export interface LicenseInfo { status: LicenseStatus; shopName: string; providerPhone: string; providerWhatsapp: string; version: string }

export interface ItemInput {
  name: string; sku: string; categoryId: number | null; supplierId: number | null; unit: string
  purchasePoisha: number; sellingPoisha: number; qtyMilli: number; thresholdMilli: number | null
}
export interface BackupStatus { thresholdMilli: number; lastBackupAt: number | null; pendingUploads: number; files: { name: string; size: number }[] }
export interface RestorePreview { token: string; items: number; sales: number; createdAt: number }

/** Error from the server: `code` is its {"error": CODE}, or NETWORK when it could not be reached. */
export class ApiError extends Error {
  constructor(public code: string, public status: number, message?: string) {
    super(message ?? code)
  }
}

/** Fired when the server says the license is no longer active, so the app shows the lock screen. */
export const LICENSE_LOST = 'ajwa:license-lost'

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  let res: Response
  try {
    res = await fetch(url, {
      method,
      headers: body instanceof FormData || body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError('NETWORK', 0)
  }
  const text = await res.text()
  const data = text ? safeJson(text) : null
  if (!res.ok) {
    const code = (data && typeof data === 'object' && 'error' in data && String(data.error)) || `HTTP_${res.status}`
    if (code === 'LICENSE') window.dispatchEvent(new Event(LICENSE_LOST))
    throw new ApiError(code, res.status, data?.message)
  }
  return data as T
}

function safeJson(text: string) {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

const get = <T>(url: string) => request<T>('GET', url)
const post = <T>(url: string, body?: unknown) => request<T>('POST', url, body ?? {})
const put = <T>(url: string, body: unknown) => request<T>('PUT', url, body)
const del = (url: string) => request<void>('DELETE', url)

export const api = {
  license: () => get<LicenseInfo>('/api/license'),
  activate: (key: string) => post<{ serial: number; expiresAt: number; backupMode: string }>('/api/license/activate', { key }),

  dashboard: () => get<{ stats: Stats; todaySalesPoisha: number; recent: SaleRow[] }>('/api/dashboard'),
  catalog: () => get<{ categories: Category[]; suppliers: Supplier[]; thresholdMilli: number }>('/api/catalog'),

  items: () => get<Item[]>('/api/items'),
  item: (id: number) => get<{ item: Item; movements: Movement[] }>(`/api/items/${id}`),
  createItem: (input: ItemInput) => post<{ id: number }>('/api/items', input),
  updateItem: (id: number, input: ItemInput) => put<{ id: number }>(`/api/items/${id}`, input),
  deleteItem: (id: number) => del(`/api/items/${id}`),
  sell: (id: number, qtyMilli: number) => post<unknown>(`/api/items/${id}/sell`, { qtyMilli }),
  stockIn: (id: number, qtyMilli: number, note: string) => post<void>(`/api/items/${id}/stock-in`, { qtyMilli, note }),
  adjust: (id: number, qtyMilli: number, note: string) => post<void>(`/api/items/${id}/adjust`, { qtyMilli, note }),

  addCategory: (name: string, parentId: number | null) => post<{ id: number }>('/api/categories', { name, parentId }),
  renameCategory: (id: number, name: string) => put<void>(`/api/categories/${id}`, { name }),
  deleteCategory: (id: number) => del(`/api/categories/${id}`),
  saveSupplier: (id: number, name: string, phone: string) =>
    id === 0 ? post<{ id: number }>('/api/suppliers', { name, phone }) : put<{ id: number }>(`/api/suppliers/${id}`, { name, phone }),
  deleteSupplier: (id: number) => del(`/api/suppliers/${id}`),

  /** Both dates inclusive, yyyy-mm-dd in shop time. */
  sales: (from: string, to: string) => get<SaleRow[]>(`/api/sales?from=${from}&to=${to}`),

  settings: () => get<BackupStatus>('/api/settings'),
  setThreshold: (milli: number) => put<void>('/api/settings/threshold', { milli }),
  backupNow: () => post<{ name: string; size: number }>('/api/backups'),
  restorePreview: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<RestorePreview>('POST', '/api/restore/preview', form)
  },
  restore: (token: string) => post<void>(`/api/restore/${token}`),
}
