import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { BrowserRouter, NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { api, LICENSE_LOST, type LicenseInfo } from './api'
import { bangla, setBangla, t, type StringKey } from './i18n'
import { Icon, LoadError, Loading, ToastProvider, type IconName } from './ui'
import Activation from './pages/Activation'
import Dashboard from './pages/Dashboard'
import Inventory from './pages/Inventory'
import ItemDetail from './pages/ItemDetail'
import ItemEdit from './pages/ItemEdit'
import Categories from './pages/Categories'
import Suppliers from './pages/Suppliers'
import Reports from './pages/Reports'
import Settings from './pages/Settings'

interface LicenseContextValue {
  info: LicenseInfo
  refresh: () => Promise<void>
  /** Switches language; the whole app re-renders, like the phone recreating its screen. */
  setLanguage: (bangla: boolean) => void
}

const LicenseContext = createContext<LicenseContextValue | null>(null)

export function useLicense(): LicenseContextValue {
  return useContext(LicenseContext)!
}

const TABS: { to: string; label: StringKey; icon: IconName }[] = [
  { to: '/', label: 'nav_home', icon: 'home' },
  { to: '/stock', label: 'nav_stock', icon: 'stock' },
  { to: '/reports', label: 'nav_reports', icon: 'reports' },
  { to: '/settings', label: 'nav_settings', icon: 'settings' },
]

export default function App() {
  const [info, setInfo] = useState<LicenseInfo | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [lang, setLang] = useState(bangla)

  const refresh = useCallback(async () => {
    try {
      setInfo(await api.license())
      setError(null)
    } catch (e) {
      setError(e)
    }
  }, [])

  useEffect(() => {
    refresh()
    // The server locks the API when the license ends; re-check so the lock screen appears.
    window.addEventListener(LICENSE_LOST, refresh)
    const timer = setInterval(refresh, 30 * 60_000)
    return () => {
      window.removeEventListener(LICENSE_LOST, refresh)
      clearInterval(timer)
    }
  }, [refresh])

  const setLanguage = useCallback((value: boolean) => {
    setBangla(value)
    setLang(value)
  }, [])

  useEffect(() => {
    document.title = t('shop_name')
  }, [lang])

  if (!info) return error ? <LoadError error={error} onRetry={refresh} /> : <Loading />

  return (
    <LicenseContext.Provider value={{ info, refresh, setLanguage }}>
      <ToastProvider key={String(lang)}>
        <BrowserRouter>
          {info.status.state === 'ACTIVE' ? <Shell /> : <Activation locked />}
        </BrowserRouter>
      </ToastProvider>
    </LicenseContext.Provider>
  )
}

function Shell() {
  const location = useLocation()
  const onTab = TABS.some((tab) => tab.to === location.pathname)
  return (
    <div className={`shell ${onTab ? 'with-tabs' : ''}`}>
      <nav className="sidenav" aria-label="main">
        <div className="brand">
          <img src="/logo.svg" alt="" width={36} height={36} />
          <span>{t('shop_name')}</span>
        </div>
        {TABS.map((tab) => (
          <NavLink key={tab.to} to={tab.to} end className="sidenav-link">
            <Icon name={tab.icon} />
            <span>{t(tab.label)}</span>
          </NavLink>
        ))}
      </nav>
      <main className="content">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/stock" element={<Inventory pickToSell={false} />} />
          <Route path="/sell" element={<Inventory pickToSell />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/categories" element={<Categories />} />
          <Route path="/suppliers" element={<Suppliers />} />
          <Route path="/license" element={<Activation locked={false} />} />
          <Route path="/item/:id" element={<ItemDetail />} />
          <Route path="/edit/:id" element={<ItemEdit />} />
          <Route path="*" element={<Dashboard />} />
        </Routes>
      </main>
      {onTab && (
        <nav className="bottomnav" aria-label="main">
          {TABS.map((tab) => (
            <NavLink key={tab.to} to={tab.to} end className="bottomnav-link">
              <Icon name={tab.icon} />
              <span>{t(tab.label)}</span>
            </NavLink>
          ))}
        </nav>
      )}
    </div>
  )
}
