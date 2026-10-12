import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useLicense } from '../App'
import { api, ApiError } from '../api'
import * as Fmt from '../fmt'
import { t, type StringKey } from '../i18n'
import { Card, copyText, errorText, Icon, TopBar, useToast } from '../ui'

const ACTIVATION_ERRORS = ['MALFORMED', 'BAD_SIGNATURE', 'WRONG_DEVICE', 'ALREADY_USED', 'TOO_OLD', 'FROM_FUTURE', 'CLOCK']

/**
 * Shown when the app is locked (no license, expired, clock set back) and, with locked=false, for
 * entering a renewal key early. Same flow as the phone: send the device code, paste the key.
 */
export default function Activation({ locked }: { locked: boolean }) {
  const { info, refresh } = useLicense()
  const navigate = useNavigate()
  const toast = useToast()
  const [key, setKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const { status } = info
  const clock = status.state === 'CLOCK_TAMPERED'
  const expired = status.state === 'EXPIRED'

  const [title, message] = expired
    ? [t('act_title_expired'), t('act_msg_expired')]
    : clock
      ? [t('act_title_clock'), t('act_msg_clock')]
      : status.state === 'ACTIVE'
        ? [t('set_enter_key'), null]
        : [t('act_title_new'), t('act_msg_new')]
  const danger = expired || clock

  const activate = async () => {
    setBusy(true)
    try {
      const l = await api.activate(key)
      toast(t('act_success', Fmt.date(l.expiresAt)))
      setKey('')
      await refresh()
      if (!locked) navigate(-1)
    } catch (e) {
      setError(e instanceof ApiError && ACTIVATION_ERRORS.includes(e.code) ? t(`err_key_${e.code}` as StringKey) : errorText(e))
    } finally {
      setBusy(false)
    }
  }

  const canPaste = typeof navigator.clipboard?.readText === 'function'

  return (
    <div className={locked ? 'lock-page' : ''}>
      {!locked && <TopBar title={t('set_enter_key')} back />}
      <div className="page narrow stack center-col">
        <img src="/logo.svg" alt="" width={88} height={88} />
        <div className="brand-name">{t('shop_name')}</div>
        <h2 className={`row gap-sm ${danger ? 'text-error' : ''}`}>
          {danger && <Icon name="lock" />}
          {title}
        </h2>
        {message && (
          <Card tone={danger ? 'error' : undefined}>
            <p className="text-center">{message}</p>
          </Card>
        )}

        <DeviceCodeCard />

        {!clock && (
          <form className="stack full" onSubmit={(e) => { e.preventDefault(); if (key.trim() && !busy) activate() }}>
            <label className={`field ${error ? 'field-error' : ''}`}>
              <span className="field-label">{t('act_key_label')}</span>
              <textarea
                className="mono"
                rows={4}
                autoCapitalize="characters"
                autoCorrect="off"
                autoComplete="off"
                spellCheck={false}
                value={key}
                onChange={(e) => { setKey(e.target.value); setError(null) }}
              />
              {error && <span className="field-hint">{error}</span>}
            </label>
            {canPaste && (
              <button type="button" className="btn btn-text self-end" onClick={async () => {
                try {
                  setKey((await navigator.clipboard.readText()).trim())
                  setError(null)
                } catch { /* permission refused: the user can long-press to paste */ }
              }}>
                {t('act_paste')}
              </button>
            )}
            <button type="submit" className="btn btn-primary btn-lg" disabled={!key.trim() || busy}>
              {busy ? t('working') : t('act_activate')}
            </button>
          </form>
        )}

        <ProviderContact />
        <p className="small muted text-center">{t('act_privacy')}</p>
      </div>
    </div>
  )
}

function whatsAppUrl(number: string, message: string) {
  return `https://wa.me/${number}?text=${encodeURIComponent(message)}`
}

function DeviceCodeCard() {
  const { info } = useLicense()
  const toast = useToast()
  const code = info.status.deviceCode
  return (
    <Card className="full">
      <div className="label">{t('act_device_code')}</div>
      <div className="row">
        <span className="device-code grow">{code}</span>
        <button className="icon-btn" aria-label={t('copied')} onClick={async () => (await copyText(code)) && toast(t('copied'))}>
          <Icon name="copy" />
        </button>
      </div>
      <p className="small">{t('act_device_hint')}</p>
      <a className="btn btn-outline full" href={whatsAppUrl(info.providerWhatsapp, t('wa_device_msg', code))} target="_blank" rel="noreferrer">
        <Icon name="send" /> {t('act_send_code')}
      </a>
    </Card>
  )
}

export function ProviderContact() {
  const { info } = useLicense()
  return (
    <Card className="full">
      <div className="label">{t('act_contact')}</div>
      <div className="big-number">{Fmt.digits(info.providerPhone)}</div>
      <div className="row gap">
        <a className="btn btn-outline grow" href={`tel:${info.providerPhone}`}>
          <Icon name="call" /> {t('act_call')}
        </a>
        <a className="btn btn-outline grow" href={whatsAppUrl(info.providerWhatsapp, t('wa_device_msg', info.status.deviceCode))} target="_blank" rel="noreferrer">
          <Icon name="send" /> {t('act_whatsapp')}
        </a>
      </div>
    </Card>
  )
}
