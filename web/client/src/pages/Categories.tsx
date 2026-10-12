import { useMemo, useState } from 'react'
import { api, type Category } from '../api'
import { t } from '../i18n'
import { CategoryTree } from '../tree'
import { ConfirmDialog, errorText, Icon, LoadError, Loading, TextInputDialog, TopBar, useLoad, useToast } from '../ui'

type Dialog = { kind: 'add'; parent: Category | null } | { kind: 'rename'; category: Category } | { kind: 'delete'; category: Category }

/** Category tree, up to 3 levels. */
export default function Categories() {
  const toast = useToast()
  const { data, error, reload } = useLoad(api.catalog)
  const tree = useMemo(() => new CategoryTree(data?.categories ?? []), [data])
  const [dialog, setDialog] = useState<Dialog | null>(null)

  const run = async (action: () => Promise<unknown>) => {
    try {
      await action()
    } catch (e) {
      toast(errorText(e, { IN_USE: 'cat_in_use' }))
    }
    reload()
  }

  const list = tree.flattened()

  return (
    <>
      <TopBar title={t('cat_title')} back />
      <div className="page">
        {!data ? (
          error ? <LoadError error={error} onRetry={reload} /> : <Loading />
        ) : list.length === 0 ? (
          <p className="center muted pad">{t('cat_empty')}</p>
        ) : (
          <ul className="card list tree">
            {list.map((c) => (
              <li key={c.id} className="list-row static" style={{ paddingLeft: 12 + 22 * (c.level - 1) }}>
                {c.level > 1 && <span className="muted"><Icon name="subdir" size={18} /></span>}
                <span className={`grow min0 ellipsis ${c.level === 1 ? 'strong' : ''}`}>{c.name}</span>
                {c.level < 3 && (
                  <button className="icon-btn" aria-label={t('cat_add_child')} title={t('cat_add_child')} onClick={() => setDialog({ kind: 'add', parent: c })}>
                    <Icon name="add" />
                  </button>
                )}
                <button className="icon-btn" aria-label={t('rename')} onClick={() => setDialog({ kind: 'rename', category: c })}><Icon name="edit" /></button>
                <button className="icon-btn" aria-label={t('delete')} onClick={() => setDialog({ kind: 'delete', category: c })}><Icon name="delete" /></button>
              </li>
            ))}
          </ul>
        )}
      </div>
      <button className="fab" onClick={() => setDialog({ kind: 'add', parent: null })}>
        <Icon name="add" /> {t('cat_add_root')}
      </button>

      {dialog?.kind === 'add' && (
        <TextInputDialog
          title={dialog.parent ? `${dialog.parent.name} › ${t('cat_add_child')}` : t('cat_add_root')}
          label={t('cat_name')}
          onClose={() => setDialog(null)}
          onSave={(name) => { setDialog(null); run(() => api.addCategory(name, dialog.parent?.id ?? null)) }}
        />
      )}
      {dialog?.kind === 'rename' && (
        <TextInputDialog
          title={t('rename')}
          label={t('cat_name')}
          initial={dialog.category.name}
          onClose={() => setDialog(null)}
          onSave={(name) => { setDialog(null); run(() => api.renameCategory(dialog.category.id, name)) }}
        />
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          title={t('delete') + '?'}
          message={dialog.category.name}
          confirm={t('delete')}
          onClose={() => setDialog(null)}
          onConfirm={() => run(() => api.deleteCategory(dialog.category.id))}
        />
      )}
    </>
  )
}
