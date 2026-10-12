import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type Item, type SaleRow, type Supplier } from '../api'
import * as Fmt from '../fmt'
import { t, unitLabel } from '../i18n'
import { stockLevel } from '../tree'
import { Card, Chip, Icon, LoadError, Loading, TopBar, useLoad } from '../ui'

interface Range { from: string; to: string }
interface SupplierRow { name: string; items: number; sellValue: number; costValue: number }

const TAB_KEY = 'ajwa.reportTab'

export default function Reports() {
  const navigate = useNavigate()
  const [tab, setTabState] = useState(() => {
    try { return Number(sessionStorage.getItem(TAB_KEY) ?? 0) } catch { return 0 }
  })
  const setTab = (i: number) => {
    setTabState(i)
    try { sessionStorage.setItem(TAB_KEY, String(i)) } catch { /* not essential */ }
  }
  const today = Fmt.today()
  const [range, setRange] = useState<Range>({ from: today, to: today })

  const stock = useLoad(() => Promise.all([api.items(), api.catalog()]))
  const sales = useLoad(() => api.sales(range.from, range.to), [range.from, range.to])

  const threshold = stock.data?.[1].thresholdMilli ?? 5000
  const low = useMemo(
    () => (stock.data?.[0] ?? []).filter((i) => stockLevel(i, threshold) !== 'OK').sort((a, b) => a.qtyMilli - b.qtyMilli),
    [stock.data, threshold],
  )
  const supplierRows = useMemo(
    () => supplierSummary(stock.data?.[0] ?? [], stock.data?.[1].suppliers ?? []),
    [stock.data],
  )

  const download = () => {
    const csv = tab === 0 ? lowStockCsv(low) : tab === 1 ? salesCsv(sales.data ?? []) : supplierCsv(supplierRows)
    const name = `Ajwa_${['LowStock', 'Sales', 'Suppliers'][tab]}_${today}.csv`
    // BOM so Excel shows Bangla text correctly.
    const url = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  const tabs = [t('rep_low_stock'), t('rep_sales'), t('rep_supplier')]
  const active = tab === 1 ? sales : stock

  return (
    <>
      <TopBar title={t('nav_reports')}>
        <button className="icon-btn" aria-label={t('rep_share')} title={t('rep_share')} onClick={download} disabled={!active.data}>
          <Icon name="download" />
        </button>
      </TopBar>
      <div className="tabs" role="tablist">
        {tabs.map((label, i) => (
          <button key={i} role="tab" aria-selected={tab === i} className={`tab ${tab === i ? 'tab-on' : ''}`} onClick={() => setTab(i)}>{label}</button>
        ))}
      </div>
      <div className="page stack">
        {tab === 1 && <SalesRange range={range} onRange={setRange} />}
        {!active.data ? (
          active.error ? <LoadError error={active.error} onRetry={active.reload} /> : <Loading />
        ) : tab === 0 ? (
          <LowStock low={low} threshold={threshold} onOpen={(id) => navigate(`/item/${id}`)} />
        ) : tab === 1 ? (
          <Sales sales={sales.data ?? []} onOpen={(id) => navigate(`/item/${id}`)} />
        ) : (
          <Suppliers rows={supplierRows} />
        )}
      </div>
    </>
  )
}

function Empty() {
  return <p className="center muted pad">{t('rep_no_data')}</p>
}

function LowStock({ low, threshold, onOpen }: { low: Item[]; threshold: number; onOpen: (id: number) => void }) {
  if (low.length === 0) return <Empty />
  return (
    <Card>
      <div className="label">{t('rep_items_count', Fmt.num(low.length))}</div>
      <ul className="list">
        {low.map((item) => {
          const out = item.qtyMilli <= 0
          return (
            <li key={item.id}>
              <button className="list-row" onClick={() => onOpen(item.id)}>
                <span className="grow min0">
                  <span className={`ellipsis ${out ? 'text-out' : ''}`}>{item.name}</span>
                  <span className="small muted">{item.sku} · ≤ {Fmt.qty(item.thresholdMilli ?? threshold)}</span>
                </span>
                <strong className={out ? 'text-out' : 'text-low'}>{Fmt.qty(item.qtyMilli)} {unitLabel(item.unit)}</strong>
              </button>
            </li>
          )
        })}
      </ul>
    </Card>
  )
}

function SalesRange({ range, onRange }: { range: Range; onRange: (r: Range) => void }) {
  const today = Fmt.today()
  const presets: [string, Range][] = [
    [t('rep_today'), { from: today, to: today }],
    [t('rep_7days'), { from: Fmt.addDays(today, -6), to: today }],
    [t('rep_month'), { from: today.slice(0, 8) + '01', to: today }],
  ]
  const isPreset = presets.some(([, r]) => r.from === range.from && r.to === range.to)
  const [picking, setPicking] = useState(!isPreset)
  return (
    <div className="stack-sm">
      <div className="chips">
        {presets.map(([label, r]) => (
          <Chip key={label} selected={!picking && r.from === range.from && r.to === range.to} onClick={() => { setPicking(false); onRange(r) }}>{label}</Chip>
        ))}
        <Chip selected={picking} onClick={() => setPicking(true)}>{t('rep_custom')}</Chip>
      </div>
      {picking && (
        <div className="grid-2">
          <label className="field">
            <span className="field-label">{t('rep_from')}</span>
            <input type="date" value={range.from} max={range.to} onChange={(e) => e.target.value && onRange({ ...range, from: e.target.value })} />
          </label>
          <label className="field">
            <span className="field-label">{t('rep_to')}</span>
            <input type="date" value={range.to} min={range.from} onChange={(e) => e.target.value && onRange({ ...range, to: e.target.value })} />
          </label>
        </div>
      )}
      <div className="label">{range.from === range.to ? Fmt.isoToDisplay(range.from) : `${Fmt.isoToDisplay(range.from)} – ${Fmt.isoToDisplay(range.to)}`}</div>
    </div>
  )
}

function Sales({ sales, onOpen }: { sales: SaleRow[]; onOpen: (id: number) => void }) {
  const revenue = sales.reduce((s, x) => s + x.totalPoisha, 0)
  const profit = revenue - sales.reduce((s, x) => s + x.costPoisha, 0)
  return (
    <>
      <Card>
        <div className="metrics">
          <Metric label={t('rep_revenue')} value={Fmt.taka(revenue)} />
          <Metric label={t('rep_profit')} value={Fmt.taka(profit)} tone={profit >= 0 ? 'good' : 'out'} />
          <Metric label={t('rep_count')} value={Fmt.num(sales.length)} />
        </div>
      </Card>
      {sales.length === 0 ? (
        <Empty />
      ) : (
        <Card>
          <ul className="list">
            {sales.map((s) => (
              <li key={s.id}>
                <button className="list-row" onClick={() => onOpen(s.itemId)}>
                  <span className="grow min0">
                    <span className="ellipsis">{s.itemName ?? '—'}</span>
                    <span className="small muted">{Fmt.qty(s.qtyMilli)} {unitLabel(s.unit)} × {Fmt.taka(s.unitPoisha)} · {Fmt.dateTime(s.soldAt)}</span>
                  </span>
                  <strong>{Fmt.taka(s.totalPoisha)}</strong>
                </button>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </>
  )
}

function Metric({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'out' }) {
  return (
    <div className="metric">
      <div className="label">{label}</div>
      <div className={`metric-value ${tone ? 'text-' + tone : ''}`}>{value}</div>
    </div>
  )
}

function supplierSummary(items: Item[], suppliers: Supplier[]): SupplierRow[] {
  const names = new Map(suppliers.map((s) => [s.id, s.name]))
  const groups = new Map<number | null, Item[]>()
  for (const i of items) groups.set(i.supplierId, [...(groups.get(i.supplierId) ?? []), i])
  return [...groups.entries()]
    .map(([id, list]) => ({
      name: (id != null && names.get(id)) || t('rep_no_supplier'),
      items: list.length,
      sellValue: list.reduce((s, i) => s + Fmt.lineTotal(i.sellingPoisha, Math.max(0, i.qtyMilli)), 0),
      costValue: list.reduce((s, i) => s + Fmt.lineTotal(i.purchasePoisha, Math.max(0, i.qtyMilli)), 0),
    }))
    .sort((a, b) => b.sellValue - a.sellValue)
}

function Suppliers({ rows }: { rows: SupplierRow[] }) {
  if (rows.length === 0) return <Empty />
  const total: SupplierRow = {
    name: t('rep_total'),
    items: rows.reduce((s, r) => s + r.items, 0),
    sellValue: rows.reduce((s, r) => s + r.sellValue, 0),
    costValue: rows.reduce((s, r) => s + r.costValue, 0),
  }
  return (
    <>
      {[...rows, total].map((r, i) => (
        <Card key={i} tone={i === rows.length ? 'primary' : undefined}>
          <div className="row">
            <h3 className="grow">{r.name}</h3>
            <span className="small">{t('rep_items_count', Fmt.num(r.items))}</span>
          </div>
          <div className="metrics">
            <Metric label={t('rep_sell_value')} value={Fmt.taka(r.sellValue)} />
            <Metric label={t('rep_cost_value')} value={Fmt.taka(r.costValue)} />
          </div>
        </Card>
      ))}
    </>
  )
}

// ---------------------------------------------------------------- CSV export (same columns as the phone)

function csvLine(...cells: (string | number | null | undefined)[]): string {
  return cells.map((c) => '"' + String(c ?? '').replace(/"/g, '""') + '"').join(',') + '\n'
}

const unitName = (u: string | null) => (u ? unitLabel(u) : '')

function lowStockCsv(items: Item[]) {
  return csvLine('Name', 'SKU', 'Quantity', 'Unit', 'Limit') +
    items.map((i) => csvLine(i.name, i.sku, Fmt.plainQty(i.qtyMilli), unitName(i.unit), i.thresholdMilli != null ? Fmt.plainQty(i.thresholdMilli) : null)).join('')
}

function salesCsv(sales: SaleRow[]) {
  const stamp = (ms: number) => new Intl.DateTimeFormat('sv-SE', { timeZone: Fmt.ZONE, dateStyle: 'short', timeStyle: 'medium' }).format(ms).replace(' ', 'T')
  return csvLine('Date', 'Item', 'Quantity', 'Unit', 'Unit price', 'Total', 'Cost') +
    sales.map((s) => csvLine(stamp(s.soldAt), s.itemName, Fmt.plainQty(s.qtyMilli), unitName(s.unit), Fmt.plainMoney(s.unitPoisha),
      Fmt.plainMoney(s.totalPoisha), Fmt.plainMoney(s.costPoisha))).join('')
}

function supplierCsv(rows: SupplierRow[]) {
  return csvLine('Supplier', 'Items', 'Value at selling price', 'Value at purchase price') +
    rows.map((r) => csvLine(r.name, r.items, Fmt.plainMoney(r.sellValue), Fmt.plainMoney(r.costValue))).join('')
}
