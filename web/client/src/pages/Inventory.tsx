import { useDeferredValue, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type Item } from '../api'
import * as Fmt from '../fmt'
import { t, unitLabel } from '../i18n'
import { CategoryTree, stockLevel, type StockLevel } from '../tree'
import { Chip, Icon, LoadError, Loading, TopBar, useLoad } from '../ui'

/** Remembered per browser tab, like the phone keeping the screen's filters. */
const FILTER_KEY = 'ajwa.stockFilters'

interface Filters { query: string; supplierId: number | null; categoryId: number | null; lowOnly: boolean }

function loadFilters(): Filters {
  try {
    return { query: '', supplierId: null, categoryId: null, lowOnly: false, ...JSON.parse(sessionStorage.getItem(FILTER_KEY) ?? '{}') }
  } catch {
    return { query: '', supplierId: null, categoryId: null, lowOnly: false }
  }
}

export default function Inventory({ pickToSell }: { pickToSell: boolean }) {
  const navigate = useNavigate()
  const { data, error, reload } = useLoad(() => Promise.all([api.items(), api.catalog()]))
  const [filters, setFiltersState] = useState<Filters>(() => (pickToSell ? { query: '', supplierId: null, categoryId: null, lowOnly: false } : loadFilters()))
  const query = useDeferredValue(filters.query)

  const setFilters = (patch: Partial<Filters>) => {
    const next = { ...filters, ...patch }
    setFiltersState(next)
    if (!pickToSell) {
      try { sessionStorage.setItem(FILTER_KEY, JSON.stringify(next)) } catch { /* not essential */ }
    }
  }

  const tree = useMemo(() => new CategoryTree(data?.[1].categories ?? []), [data])
  const suppliers = data?.[1].suppliers ?? []
  const threshold = data?.[1].thresholdMilli ?? 5000

  const items = useMemo(() => {
    if (!data) return null
    const q = Fmt.toAscii(query.trim()).toLowerCase()
    const cats = filters.categoryId != null ? tree.withDescendants(filters.categoryId) : null
    return data[0].filter((item) =>
      (!q || item.name.toLowerCase().includes(q) || item.sku.toLowerCase().includes(q)) &&
      (filters.supplierId == null || item.supplierId === filters.supplierId) &&
      (cats == null || (item.categoryId != null && cats.has(item.categoryId))) &&
      (!filters.lowOnly || stockLevel(item, threshold) !== 'OK'))
  }, [data, query, filters.supplierId, filters.categoryId, filters.lowOnly, tree, threshold])

  return (
    <>
      <TopBar title={pickToSell ? t('inv_pick_item') : t('nav_stock')} back={pickToSell} />
      <div className="page stack-sm">
        <div className="search">
          <Icon name="search" />
          <input
            type="search"
            placeholder={t('inv_search')}
            value={filters.query}
            autoFocus={pickToSell}
            onChange={(e) => setFilters({ query: e.target.value })}
          />
          {filters.query && (
            <button className="icon-btn" aria-label={t('cancel')} onClick={() => setFilters({ query: '' })}>
              <Icon name="clear" size={20} />
            </button>
          )}
        </div>
        <div className="chips">
          <select className={`chip ${filters.supplierId != null ? 'chip-on' : ''}`} value={filters.supplierId ?? ''}
            onChange={(e) => setFilters({ supplierId: e.target.value ? Number(e.target.value) : null })}>
            <option value="">{t('inv_all_suppliers')}</option>
            {suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
          <select className={`chip ${filters.categoryId != null ? 'chip-on' : ''}`} value={filters.categoryId ?? ''}
            onChange={(e) => setFilters({ categoryId: e.target.value ? Number(e.target.value) : null })}>
            <option value="">{t('inv_all_categories')}</option>
            {tree.flattened().map((c) => <option key={c.id} value={c.id}>{'   '.repeat(c.level - 1) + c.name}</option>)}
          </select>
          <Chip selected={filters.lowOnly} onClick={() => setFilters({ lowOnly: !filters.lowOnly })}>{t('inv_low_only')}</Chip>
        </div>

        {!items ? (
          error ? <LoadError error={error} onRetry={reload} /> : <Loading />
        ) : (
          <>
            <div className="small muted">{t('inv_count', Fmt.num(items.length))}</div>
            {items.length === 0 ? (
              <p className="center muted pad">{t('inv_empty')}</p>
            ) : (
              <ul className="cards">
                {items.map((item) => (
                  <ItemRow
                    key={item.id}
                    item={item}
                    level={stockLevel(item, threshold)}
                    category={tree.path(item.categoryId)}
                    supplier={suppliers.find((s) => s.id === item.supplierId)?.name}
                    onClick={() => navigate(`/item/${item.id}${pickToSell ? '?sell=1' : ''}`, { replace: pickToSell })}
                  />
                ))}
              </ul>
            )}
          </>
        )}
      </div>
      {!pickToSell && (
        <button className="fab" onClick={() => navigate('/edit/0')}>
          <Icon name="add" /> {t('action_add_item')}
        </button>
      )}
    </>
  )
}

function ItemRow({ item, level, category, supplier, onClick }: { item: Item; level: StockLevel; category: string; supplier?: string; onClick: () => void }) {
  return (
    <li>
      <button className={`card clickable item-row ${level === 'OUT' ? 'card-error' : ''}`} onClick={onClick}>
        <span className="grow min0">
          <span className={`item-name ellipsis ${level === 'OUT' ? 'text-out' : ''}`}>{item.name}</span>
          <span className="small muted ellipsis">{[item.sku, supplier, category || null].filter(Boolean).join(' · ')}</span>
        </span>
        <span className="right">
          <strong className={level === 'OUT' ? 'text-out' : level === 'LOW' ? 'text-low' : ''}>
            {Fmt.qty(item.qtyMilli)} {unitLabel(item.unit)}
          </strong>
          <span className="small">{Fmt.taka(item.sellingPoisha)}</span>
        </span>
      </button>
    </li>
  )
}
