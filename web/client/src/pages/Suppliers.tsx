import { useState } from 'react'
import { api, type Supplier } from '../api'
import * as Fmt from '../fmt'
import { t } from '../i18n'
import { ConfirmDialog, errorText, Icon, LoadError, Loading, TextInputDialog, TopBar, useLoad, useToast } from '../ui'

export default function Suppliers() {
  const toast = useToast()
  const { data, error, reload } = useLoad(api.catalog)
  const [editing, setEditing] = useState<Supplier | null>(null)
  const [deleting, setDeleting] = useState<Supplier | null>(null)

  const run = async (action: () => Promise<unknown>) => {
    try {
      await action()
    } catch (e) {
      toast(errorText(e, { IN_USE: 'sup_in_use', NAME_TAKEN: 'name_taken' }))
    }
    reload()
  }

  return (
    <>
      <TopBar title={t('sup_title')} back />
      <div className="page">
        {!data ? (
          error ? <LoadError error={error} onRetry={reload} /> : <Loading />
        ) : data.suppliers.length === 0 ? (
          <p className="center muted pad">{t('sup_empty')}</p>
        ) : (
          <ul className="card list">
            {data.suppliers.map((s) => (
              <li key={s.id} className="list-row static">
                <span className="grow min0">
                  <span className="strong ellipsis">{s.name}</span>
                  {s.phone && <a className="small" href={`tel:${s.phone}`}>{Fmt.digits(s.phone)}</a>}
                </span>
                <button className="icon-btn" aria-label={t('edit')} onClick={() => setEditing(s)}><Icon name="edit" /></button>
                <button className="icon-btn" aria-label={t('delete')} onClick={() => setDeleting(s)}><Icon name="delete" /></button>
              </li>
            ))}
          </ul>
        )}
      </div>
      <button className="fab" onClick={() => setEditing({ id: 0, name: '', phone: null, note: null })}>
        <Icon name="add" /> {t('sup_add')}
      </button>

      {editing && (
        <TextInputDialog
          title={editing.id === 0 ? t('sup_add') : t('edit')}
          label={t('cat_name')}
          initial={editing.name}
          secondLabel={t('sup_phone')}
          secondInitial={editing.phone ?? ''}
          onClose={() => setEditing(null)}
          onSave={(name, phone) => { setEditing(null); run(() => api.saveSupplier(editing.id, name, phone)) }}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title={t('delete') + '?'}
          message={deleting.name}
          confirm={t('delete')}
          onClose={() => setDeleting(null)}
          onConfirm={() => run(() => api.deleteSupplier(deleting.id))}
        />
      )}
    </>
  )
}
