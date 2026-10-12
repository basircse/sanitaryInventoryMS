import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useLicense } from '../App'
import { api, type RestorePreview } from '../api'
import * as Fmt from '../fmt'
import { bangla, t, type StringKey } from '../i18n'
import { Card, Chip, errorText, Icon, LoadError, Loading, Modal, NumberField, TopBar, useLoad, useToast } from '../ui'
import { ProviderContact } from './Activation'

export default function Settings() {
  const { info, setLanguage } = useLicense()
  const navigate = useNavigate()
  const toast = useToast()
  const { data, error, reload } = useLoad(api.settings)
  const [busy, setBusy] = useState(false)
  const [candidate, setCandidate] = useState<RestorePreview | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const status = info.status

  const backupNow = async () => {
    setBusy(true)
    try {
      await api.backupNow()
      toast(t('set_backup_done'))
      reload()
    } catch (e) {
      toast(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  const pickFile = async (file: File | undefined) => {
    if (!file) return
    setBusy(true)
    try {
      setCandidate(await api.restorePreview(file))
    } catch (e) {
      toast(t('restore_failed', errorText(e)))
    } finally {
      setBusy(false)
      if (fileInput.current) fileInput.current.value = ''
    }
  }

  const restore = async (token: string) => {
    setCandidate(null)
    setBusy(true)
    try {
      await api.restore(token)
      toast(t('restore_done'))
      reload()
    } catch (e) {
      toast(t('restore_failed', errorText(e)))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <TopBar title={t('nav_settings')} />
      <div className="page stack">
        <Card>
          <h3>{t('set_language')}</h3>
          <div className="chips">
            <Chip selected={bangla} onClick={() => setLanguage(true)}>বাংলা</Chip>
            <Chip selected={!bangla} onClick={() => setLanguage(false)}>English</Chip>
          </div>
        </Card>

        {!data ? (
          error ? <LoadError error={error} onRetry={reload} /> : <Loading />
        ) : (
          <ThresholdCard key={data.thresholdMilli} threshold={data.thresholdMilli} onSaved={() => { toast(t('saved_ok')); reload() }} />
        )}

        <Card>
          <h3>{t('set_catalog')}</h3>
          <button className="link-row" onClick={() => navigate('/categories')}><span>{t('cat_title')}</span><Icon name="chevron" /></button>
          <button className="link-row" onClick={() => navigate('/suppliers')}><span>{t('sup_title')}</span><Icon name="chevron" /></button>
        </Card>

        <Card>
          <h3>{t('set_backup')}</h3>
          {status.backupMode && <p>{t('set_backup_mode', t(`mode_${status.backupMode}` as StringKey))}</p>}
          {data && (
            <>
              <p>{t('set_last_backup', data.lastBackupAt ? Fmt.dateTime(data.lastBackupAt) : t('set_never'))}</p>
              {data.pendingUploads > 0 && <p className="text-low">{t('set_pending_upload', Fmt.num(data.pendingUploads))}</p>}
            </>
          )}
          <div className="row gap wrap">
            <button className="btn btn-tonal" disabled={busy} onClick={backupNow}>{busy ? t('working') : t('set_backup_now')}</button>
            <button className="btn btn-outline" disabled={busy} onClick={() => fileInput.current?.click()}>
              <Icon name="upload" /> {t('set_restore')}
            </button>
            <input ref={fileInput} type="file" hidden accept=".ajwb,.ajwr,application/octet-stream" onChange={(e) => pickFile(e.target.files?.[0])} />
          </div>
          <p className="small muted">{t('set_restore_hint')}</p>
          {data && data.files.length > 0 && (
            <>
              <div className="label">{t('set_backup_files')}</div>
              <ul className="list">
                {data.files.map((f) => (
                  <li key={f.name}>
                    <a className="list-row" href={`/api/backups/${f.name}`} download>
                      <span className="grow min0 ellipsis mono small">{f.name}</span>
                      <span className="small muted">{Fmt.num(Math.max(1, Math.round(f.size / 1024)))} KB</span>
                      <Icon name="download" size={20} />
                    </a>
                  </li>
                ))}
              </ul>
            </>
          )}
        </Card>

        <Card>
          <h3>{t('set_license')}</h3>
          {status.serial != null && status.expiresAt != null && (
            <p>{t('set_license_info', Fmt.digits(String(status.serial)), Fmt.date(status.expiresAt))}</p>
          )}
          <p className="mono">{t('set_device', status.deviceCode)}</p>
          <button className="btn btn-outline self-start" onClick={() => navigate('/license')}>{t('set_enter_key')}</button>
        </Card>

        <ProviderContact />
        <p className="small muted text-center">{t('set_version', Fmt.digits(info.version))}</p>
      </div>

      {candidate && (
        <Modal
          title={t('restore_confirm_title')}
          onClose={() => setCandidate(null)}
          actions={
            <>
              <button className="btn btn-text" onClick={() => setCandidate(null)}>{t('cancel')}</button>
              <button className="btn btn-text danger strong" onClick={() => restore(candidate.token)}>{t('confirm')}</button>
            </>
          }
        >
          <p>{t('restore_confirm_msg', Fmt.num(candidate.items), Fmt.num(candidate.sales), Fmt.dateTime(candidate.createdAt))}</p>
        </Modal>
      )}
    </>
  )
}

function ThresholdCard({ threshold, onSaved }: { threshold: number; onSaved: () => void }) {
  const toast = useToast()
  const [text, setText] = useState(Fmt.plainQty(threshold))
  const parsed = Fmt.parseQty(text)
  return (
    <Card>
      <h3>{t('set_threshold')}</h3>
      <form className="row gap align-end" onSubmit={async (e) => {
        e.preventDefault()
        if (parsed == null || parsed === threshold) return
        try {
          await api.setThreshold(parsed)
          onSaved()
        } catch (err) {
          toast(errorText(err))
        }
      }}>
        <div className="grow">
          <NumberField label={t('set_threshold_desc')} value={text} onChange={setText} error={text.trim() && parsed == null ? t('err_number') : null} />
        </div>
        <button type="submit" className="btn btn-tonal" disabled={parsed == null || parsed === threshold}>{t('save')}</button>
      </form>
    </Card>
  )
}
