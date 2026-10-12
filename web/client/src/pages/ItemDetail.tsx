import { useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, ApiError, type Item, type Movement } from '../api'
import * as Fmt from '../fmt'
import { t, unitLabel, type StringKey } from '../i18n'
import { CategoryTree, stockLevel } from '../tree'
import { Card, ConfirmDialog, errorText, Field, Icon, LoadError, Loading, Modal, NumberField, TopBar, useLoad, useToast } from '../ui'

type QtyKind = 'SELL' | 'STOCK_IN' | 'ADJUST'

export default function ItemDetail() {
  const id = Number(useParams().id)
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const { data, error, reload } = useLoad(() => Promise.all([api.item(id), api.catalog()]), [id])
  const [dialog, setDialog] = useState<QtyKind | null>(params.get('sell') ? 'SELL' : null)
  const [confirmDelete, setConfirmDelete] = useState(false)

  if (!data) {
    return (
      <>
        <TopBar title="" back />
        {error ? (error instanceof ApiError && error.code === 'NOT_FOUND' ? <p className="center pad">{t('item_not_found')}</p> : <LoadError error={error} onRetry={reload} />) : <Loading />}
      </>
    )
  }

  const [{ item, movements }, catalog] = data
  const tree = new CategoryTree(catalog.categories)
  const unit = unitLabel(item.unit)
  const level = stockLevel(item, catalog.thresholdMilli)

  const closeDialog = () => {
    setDialog(null)
    if (params.get('sell')) setParams({}, { replace: true })
  }

  return (
    <>
      <TopBar title={item.name} back>
        <button className="icon-btn" aria-label={t('edit')} onClick={() => navigate(`/edit/${item.id}`)}><Icon name="edit" /></button>
        <button className="icon-btn" aria-label={t('delete')} onClick={() => setConfirmDelete(true)}><Icon name="delete" /></button>
      </TopBar>
      <div className="page stack">
        <Card>
          <div className="label">{t('det_in_stock')}</div>
          <div className={`stat-huge ${level === 'OUT' ? 'text-out' : level === 'LOW' ? 'text-low' : 'text-good'}`}>
            {Fmt.qty(item.qtyMilli)} {unit}
          </div>
          <dl className="info">
            <Info label={t('f_sku')} value={item.sku} />
            <Info label={t('f_selling_price')} value={Fmt.taka(item.sellingPoisha)} />
            <Info label={t('f_purchase_price')} value={Fmt.taka(item.purchasePoisha)} />
            <Info label={t('f_supplier')} value={catalog.suppliers.find((s) => s.id === item.supplierId)?.name ?? '—'} />
            <Info label={t('f_category')} value={tree.path(item.categoryId) || '—'} />
            <Info label={t('f_threshold')} value={Fmt.qty(item.thresholdMilli ?? catalog.thresholdMilli) + (item.thresholdMilli == null ? ' *' : '')} />
          </dl>
        </Card>

        <button className="btn btn-primary btn-lg" disabled={item.qtyMilli <= 0} onClick={() => setDialog('SELL')}>{t('det_sell')}</button>
        <div className="grid-2">
          <button className="btn btn-tonal" onClick={() => setDialog('STOCK_IN')}>{t('det_stock_in')}</button>
          <button className="btn btn-outline" onClick={() => setDialog('ADJUST')}>{t('det_adjust')}</button>
        </div>

        <Card>
          <h3>{t('det_history')}</h3>
          <ul className="list">
            {movements.map((m) => <MovementRow key={m.id} m={m} unit={unit} />)}
          </ul>
        </Card>
      </div>

      {dialog && (
        <QuantityDialog
          kind={dialog}
          item={item}
          unit={unit}
          onClose={closeDialog}
          onDone={(msg) => { closeDialog(); toast(msg); reload() }}
        />
      )}
      {confirmDelete && (
        <ConfirmDialog
          title={t('delete_item_q')}
          message={item.name}
          confirm={t('delete')}
          onClose={() => setConfirmDelete(false)}
          onConfirm={async () => {
            try {
              await api.deleteItem(item.id)
              navigate(-1)
            } catch (e) {
              toast(errorText(e, { HAS_SALES: 'delete_item_blocked' }))
            }
          }}
        />
      )}
    </>
  )
}

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div className="info-row">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function MovementRow({ m, unit }: { m: Movement; unit: string }) {
  const label = t((`mv_${m.type}` as StringKey))
  const sign = m.qtyDeltaMilli > 0 ? '+' : ''
  return (
    <li className="list-row static">
      <span className="grow min0">
        <span>{label}{m.note ? ` · ${m.note}` : ''}</span>
        <span className="small muted">{Fmt.dateTime(m.at)}</span>
      </span>
      <strong className={m.qtyDeltaMilli < 0 ? 'text-out' : 'text-good'}>{sign}{Fmt.qty(m.qtyDeltaMilli)} {unit}</strong>
    </li>
  )
}

function QuantityDialog({ kind, item, unit, onClose, onDone }: { kind: QtyKind; item: Item; unit: string; onClose: () => void; onDone: (msg: string) => void }) {
  const [qtyText, setQtyText] = useState(kind === 'ADJUST' ? Fmt.plainQty(item.qtyMilli) : '')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const qty = Fmt.parseQty(qtyText)
  const notEnough = t('err_not_enough', `${Fmt.qty(item.qtyMilli)} ${unit}`)

  const title = kind === 'SELL' ? t('sell_title', item.name) : kind === 'STOCK_IN' ? t('stockin_title', item.name) : t('adjust_title', item.name)
  const label = kind === 'ADJUST' ? t('adjust_new_qty') : t('qty_label', unit)

  const submit = async () => {
    const err = qty == null ? t('err_number')
      : kind !== 'ADJUST' && qty <= 0 ? t('err_qty_positive')
        : kind === 'SELL' && qty > item.qtyMilli ? notEnough
          : null
    setError(err)
    if (err || qty == null || busy) return
    setBusy(true)
    try {
      if (kind === 'SELL') await api.sell(item.id, qty)
      else if (kind === 'STOCK_IN') await api.stockIn(item.id, qty, note)
      else await api.adjust(item.id, qty, note)
      onDone(kind === 'SELL' ? t('sold_ok') : t('saved_ok'))
    } catch (e) {
      // Someone else may have sold the last pieces from another phone.
      setError(e instanceof ApiError && e.code === 'NOT_ENOUGH_STOCK' ? t('err_not_enough', '—') : errorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title={title}
      onClose={onClose}
      actions={
        <>
          <button className="btn btn-text" onClick={onClose}>{t('cancel')}</button>
          <button className="btn btn-text" disabled={busy} onClick={submit}>{busy ? t('working') : t('confirm')}</button>
        </>
      }
    >
      <form className="stack" onSubmit={(e) => { e.preventDefault(); submit() }}>
        <NumberField label={label} value={qtyText} onChange={(v) => { setQtyText(v); setError(null) }} error={error} autoFocus />
        {kind === 'SELL' && qty != null && qty > 0 && (
          <div className="sell-total">{t('sell_total', Fmt.taka(Fmt.lineTotal(item.sellingPoisha, qty)))}</div>
        )}
        {kind !== 'SELL' && (
          <Field label={kind === 'ADJUST' ? t('adjust_reason') : t('note_label')}>
            <input value={note} onChange={(e) => setNote(e.target.value)} />
          </Field>
        )}
        <button type="submit" hidden />
      </form>
    </Modal>
  )
}
