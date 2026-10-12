import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type ItemInput } from '../api'
import * as Fmt from '../fmt'
import { t, unitLabel } from '../i18n'
import { CategoryTree } from '../tree'
import { Chip, errorText, Field, LoadError, Loading, NumberField, SelectField, TopBar, useLoad, useToast } from '../ui'

const UNITS = ['PCS', 'BOX', 'FT', 'SET']

/** Add (id 0) or edit an item. Stock of an existing item changes only via sell / stock in / adjust. */
export default function ItemEdit() {
  const itemId = Number(useParams().id)
  const isNew = itemId === 0
  const navigate = useNavigate()
  const toast = useToast()
  const { data, error, reload } = useLoad(() => Promise.all([isNew ? null : api.item(itemId), api.catalog()]), [itemId])
  const tree = useMemo(() => new CategoryTree(data?.[1].categories ?? []), [data])

  const [loaded, setLoaded] = useState(isNew)
  const [name, setName] = useState('')
  const [sku, setSku] = useState('')
  const [cat, setCat] = useState<(number | null)[]>([null, null, null])
  const [supplierId, setSupplierId] = useState<number | null>(null)
  const [unit, setUnit] = useState('PCS')
  const [purchase, setPurchase] = useState('')
  const [selling, setSelling] = useState('')
  const [qty, setQty] = useState('')
  const [threshold, setThreshold] = useState('')
  const [showErrors, setShowErrors] = useState(false)
  const [skuError, setSkuError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Load the existing item once into the editable fields.
  useEffect(() => {
    if (loaded || !data?.[0]) return
    const item = data[0].item
    const chain = tree.chain(item.categoryId)
    setName(item.name)
    setSku(item.sku)
    setCat([chain[0]?.id ?? null, chain[1]?.id ?? null, chain[2]?.id ?? null])
    setSupplierId(item.supplierId)
    setUnit(item.unit)
    setPurchase(Fmt.plainMoney(item.purchasePoisha))
    setSelling(Fmt.plainMoney(item.sellingPoisha))
    setThreshold(item.thresholdMilli != null ? Fmt.plainQty(item.thresholdMilli) : '')
    setLoaded(true)
  }, [data, loaded, tree])

  if (!data || !loaded) {
    return (
      <>
        <TopBar title={t(isNew ? 'item_new' : 'item_edit')} back />
        {error ? <LoadError error={error} onRetry={reload} /> : <Loading />}
      </>
    )
  }

  const suppliers = data[1].suppliers
  const required = t('err_required')
  const moneyError = (text: string) => !showErrors ? null : !text.trim() ? required : Fmt.parseMoney(text) == null ? t('err_number') : null
  const nameError = showErrors && !name.trim() ? required : null
  const qtyError = showErrors && qty.trim() && Fmt.parseQty(qty) == null ? t('err_number') : null
  const thresholdError = showErrors && threshold.trim() && Fmt.parseQty(threshold) == null ? t('err_number') : null

  const save = async () => {
    setShowErrors(true)
    const p = Fmt.parseMoney(purchase)
    const s = Fmt.parseMoney(selling)
    const q = qty.trim() ? Fmt.parseQty(qty) : 0
    const th = threshold.trim() ? Fmt.parseQty(threshold) : null
    if (!name.trim() || p == null || s == null || q == null || (threshold.trim() && th == null) || busy) return
    const input: ItemInput = {
      name, sku, categoryId: cat[2] ?? cat[1] ?? cat[0], supplierId, unit,
      purchasePoisha: p, sellingPoisha: s, qtyMilli: q, thresholdMilli: th,
    }
    setBusy(true)
    try {
      const { id } = isNew ? await api.createItem(input) : await api.updateItem(itemId, input)
      if (isNew) navigate(`/item/${id}`, { replace: true })
      else navigate(-1)
    } catch (e) {
      if (e instanceof ApiError && e.code === 'SKU_TAKEN') setSkuError(t('err_sku_taken'))
      else toast(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  const none = t('none')
  const setLevel = (level: number, id: number | null) => setCat((c) => c.map((v, i) => (i < level ? v : i === level ? id : null)))

  return (
    <>
      <TopBar title={t(isNew ? 'item_new' : 'item_edit')} back />
      <form className="page stack form" onSubmit={(e) => { e.preventDefault(); save() }}>
        <Field label={t('f_name')} error={nameError}>
          <input value={name} onChange={(e) => setName(e.target.value)} autoCapitalize="words" />
        </Field>
        <Field label={t('f_sku')} error={skuError} hint={t('f_sku_auto')}>
          <input value={sku} onChange={(e) => { setSku(e.target.value.toUpperCase()); setSkuError(null) }} autoCapitalize="characters" />
        </Field>

        <SelectField label={t('f_category')} options={tree.children(null)} value={cat[0]} optionLabel={(c) => c.name} onChange={(id) => setLevel(0, id)} noneLabel={none} />
        {cat[0] != null && tree.children(cat[0]).length > 0 && (
          <SelectField label={t('f_subcategory')} options={tree.children(cat[0])} value={cat[1]} optionLabel={(c) => c.name} onChange={(id) => setLevel(1, id)} noneLabel={none} />
        )}
        {cat[1] != null && tree.children(cat[1]).length > 0 && (
          <SelectField label={t('f_subsubcategory')} options={tree.children(cat[1])} value={cat[2]} optionLabel={(c) => c.name} onChange={(id) => setLevel(2, id)} noneLabel={none} />
        )}
        <SelectField label={t('f_supplier')} options={suppliers} value={supplierId} optionLabel={(s) => s.name} onChange={setSupplierId} noneLabel={none} />

        <div className="field">
          <span className="field-label">{t('f_unit')}</span>
          <div className="chips">
            {UNITS.map((u) => <Chip key={u} selected={unit === u} onClick={() => setUnit(u)}>{unitLabel(u)}</Chip>)}
          </div>
        </div>

        <div className="grid-2">
          <NumberField label={t('f_purchase_price')} value={purchase} onChange={setPurchase} error={moneyError(purchase)} />
          <NumberField label={t('f_selling_price')} value={selling} onChange={setSelling} error={moneyError(selling)} />
        </div>
        {isNew && <NumberField label={`${t('f_initial_qty')} (${unitLabel(unit)})`} value={qty} onChange={setQty} error={qtyError} />}
        <NumberField label={t('f_threshold')} value={threshold} onChange={setThreshold} error={thresholdError}
          hint={t('f_threshold_hint', Fmt.qty(data[1].thresholdMilli))} />

        <button type="submit" className="btn btn-primary btn-lg" disabled={busy}>{busy ? t('working') : t('save')}</button>
      </form>
    </>
  )
}
