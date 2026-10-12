import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from './api'
import { t, type StringKey } from './i18n'

// ------------------------------------------------------------------ icons (Material paths)

const ICONS = {
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  stock: 'M20 2H4c-1 0-2 .9-2 2v3.01c0 .72.43 1.34 1 1.69V20c0 1.1 1.1 2 2 2h14c.9 0 2-.9 2-2V8.7c.57-.35 1-.97 1-1.69V4c0-1.1-1-2-2-2zm-5 12H9v-2h6v2zm5-7H4V4l16-.02V7z',
  reports: 'M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z',
  settings: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
  add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  search: 'M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
  edit: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.996.996 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
  delete: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
  copy: 'M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z',
  call: 'M20.01 15.38c-1.23 0-2.42-.2-3.53-.56a.977.977 0 0 0-1.01.24l-1.57 1.97c-2.83-1.35-5.48-3.9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02-.37-1.11-.56-2.3-.56-3.53 0-.54-.45-.99-.99-.99H4.19C3.65 3 3 3.24 3 3.99 3 13.28 10.73 21 20.01 21c.71 0 .99-.63.99-1.18v-3.45c0-.54-.45-.99-.99-.99z',
  send: 'M2.01 21 23 12 2.01 3 2 10l15 2-15 2z',
  lock: 'M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z',
  warning: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
  chevron: 'M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z',
  download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
  clear: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  cart: 'M7 18c-1.1 0-1.99.9-1.99 2S5.9 22 7 22s2-.9 2-2-.9-2-2-2zM1 2v2h2l3.6 7.59-1.35 2.45c-.16.28-.25.61-.25.96 0 1.1.9 2 2 2h12v-2H7.42c-.14 0-.25-.11-.25-.25l.03-.12.9-1.63h7.45c.75 0 1.41-.41 1.75-1.03l3.58-6.49A1.003 1.003 0 0 0 20 4H5.21l-.94-2H1zm16 16c-1.1 0-1.99.9-1.99 2s.89 2 1.99 2 2-.9 2-2-.9-2-2-2z',
  subdir: 'M19 15l-6 6-1.42-1.42L15.17 16H4V4h2v10h9.17l-3.59-3.58L13 9l6 6z',
  upload: 'M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z',
}

export type IconName = keyof typeof ICONS

export function Icon({ name, size = 22 }: { name: IconName; size?: number }) {
  return (
    <svg className="icon" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path d={ICONS[name]} fill="currentColor" />
    </svg>
  )
}

// ------------------------------------------------------------------ data loading

/**
 * Loads data and reloads it when the tab regains focus, standing in for the phone app's live
 * database flows (another phone may have sold something meanwhile).
 */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | undefined>(undefined)
  const [error, setError] = useState<unknown>(null)
  const loadRef = useRef(load)
  loadRef.current = load

  const reload = useCallback(async () => {
    try {
      setData(await loadRef.current())
      setError(null)
    } catch (e) {
      setError(e)
    }
  }, [])

  useEffect(() => {
    setData(undefined)
    reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => {
    const onVisible = () => document.visibilityState === 'visible' && reload()
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [reload])

  return { data, error, reload }
}

/** Turns any error into a message for the user. */
export function errorText(e: unknown, byCode: Partial<Record<string, StringKey>> = {}): string {
  if (e instanceof ApiError) {
    const key = byCode[e.code]
    if (key) return t(key)
    if (e.code === 'NETWORK') return t('err_network')
    if (e.code === 'BACKUP' && e.message) return e.message
  }
  return t('err_generic')
}

// ------------------------------------------------------------------ toast

const ToastContext = createContext<(msg: string) => void>(() => {})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [msg, setMsg] = useState<{ text: string; id: number } | null>(null)
  const show = useCallback((text: string) => setMsg({ text, id: Date.now() }), [])
  useEffect(() => {
    if (!msg) return
    const timer = setTimeout(() => setMsg(null), 3500)
    return () => clearTimeout(timer)
  }, [msg])
  return (
    <ToastContext.Provider value={show}>
      {children}
      {msg && (
        <div className="toast" role="status" key={msg.id}>
          {msg.text}
        </div>
      )}
    </ToastContext.Provider>
  )
}

export const useToast = () => useContext(ToastContext)

// ------------------------------------------------------------------ layout pieces

export function TopBar({ title, back, children }: { title: string; back?: boolean; children?: ReactNode }) {
  const navigate = useNavigate()
  return (
    <header className="topbar">
      {back && (
        <button className="icon-btn" onClick={() => (history.length > 1 ? navigate(-1) : navigate('/'))} aria-label={t('back')}>
          <Icon name="back" />
        </button>
      )}
      <h1 className="topbar-title">{title}</h1>
      {children && <div className="topbar-actions">{children}</div>}
    </header>
  )
}

export function Card({ children, tone, className = '', onClick }: { children: ReactNode; tone?: 'error' | 'primary'; className?: string; onClick?: () => void }) {
  const cls = `card ${tone ? 'card-' + tone : ''} ${onClick ? 'clickable' : ''} ${className}`
  return onClick ? (
    <button type="button" className={cls} onClick={onClick}>
      {children}
    </button>
  ) : (
    <div className={cls}>{children}</div>
  )
}

export function Loading() {
  return <div className="center muted pad">{t('working')}</div>
}

export function LoadError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="center pad stack">
      <p>{errorText(error)}</p>
      <button className="btn btn-tonal" onClick={onRetry}>
        {t('ok')}
      </button>
    </div>
  )
}

export function Modal({ title, children, actions, onClose }: { title: string; children: ReactNode; actions: ReactNode; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    document.body.classList.add('modal-open')
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.classList.remove('modal-open')
    }
  }, [onClose])
  return (
    <div className="scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <h2 className="modal-title">{title}</h2>
        <div className="modal-body">{children}</div>
        <div className="modal-actions">{actions}</div>
      </div>
    </div>
  )
}

export function ConfirmDialog({ title, message, confirm, onConfirm, onClose }: { title: string; message: string; confirm: string; onConfirm: () => void; onClose: () => void }) {
  return (
    <Modal
      title={title}
      onClose={onClose}
      actions={
        <>
          <button className="btn btn-text" onClick={onClose}>{t('cancel')}</button>
          <button className="btn btn-text danger" onClick={() => { onClose(); onConfirm() }}>{confirm}</button>
        </>
      }
    >
      <p>{message}</p>
    </Modal>
  )
}

/** One or two text fields (name, optional phone) in a dialog. */
export function TextInputDialog(props: {
  title: string; label: string; initial?: string; secondLabel?: string; secondInitial?: string
  onSave: (value: string, second: string) => void; onClose: () => void
}) {
  const [value, setValue] = useState(props.initial ?? '')
  const [second, setSecond] = useState(props.secondInitial ?? '')
  const save = () => value.trim() && props.onSave(value.trim(), second.trim())
  return (
    <Modal
      title={props.title}
      onClose={props.onClose}
      actions={
        <>
          <button className="btn btn-text" onClick={props.onClose}>{t('cancel')}</button>
          <button className="btn btn-text" disabled={!value.trim()} onClick={save}>{t('save')}</button>
        </>
      }
    >
      <form className="stack" onSubmit={(e) => { e.preventDefault(); save() }}>
        <Field label={props.label}>
          <input autoFocus value={value} onChange={(e) => setValue(e.target.value)} />
        </Field>
        {props.secondLabel && (
          <Field label={props.secondLabel}>
            <input type="tel" value={second} onChange={(e) => setSecond(e.target.value)} />
          </Field>
        )}
        <button type="submit" hidden />
      </form>
    </Modal>
  )
}

export function Field({ label, error, hint, children }: { label: string; error?: string | null; hint?: string; children: ReactNode }) {
  return (
    <label className={`field ${error ? 'field-error' : ''}`}>
      <span className="field-label">{label}</span>
      {children}
      {(error || hint) && <span className="field-hint">{error || hint}</span>}
    </label>
  )
}

/** Numeric text input that brings up the number keyboard on phones (accepts Bangla digits too). */
export function NumberField({ label, value, onChange, error, hint, autoFocus }: { label: string; value: string; onChange: (v: string) => void; error?: string | null; hint?: string; autoFocus?: boolean }) {
  return (
    <Field label={label} error={error} hint={hint}>
      <input inputMode="decimal" autoComplete="off" autoFocus={autoFocus} value={value} onChange={(e) => onChange(e.target.value)} />
    </Field>
  )
}

export function SelectField<T extends { id: number }>({ label, options, value, optionLabel, onChange, noneLabel }: {
  label: string; options: T[]; value: number | null; optionLabel: (o: T) => string; onChange: (id: number | null) => void; noneLabel: string
}) {
  return (
    <Field label={label}>
      <select value={value ?? ''} onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}>
        <option value="">{noneLabel}</option>
        {options.map((o) => (
          <option key={o.id} value={o.id}>{optionLabel(o)}</option>
        ))}
      </select>
    </Field>
  )
}

export function Chip({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" className={`chip ${selected ? 'chip-on' : ''}`} aria-pressed={selected} onClick={onClick}>
      {children}
    </button>
  )
}

/** Copies text; falls back to a hidden textarea where the Clipboard API is unavailable (plain http on the LAN). */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    ta.remove()
    return ok
  }
}
