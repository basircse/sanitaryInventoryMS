import { useNavigate } from 'react-router-dom'
import { useLicense } from '../App'
import { api } from '../api'
import * as Fmt from '../fmt'
import { t, unitLabel } from '../i18n'
import { Card, Icon, LoadError, Loading, useLoad } from '../ui'

export default function Dashboard() {
  const { info } = useLicense()
  const navigate = useNavigate()
  const { data, error, reload } = useLoad(api.dashboard)
  const status = info.status

  return (
    <div className="page stack">
      <header className="row gap">
        <img src="/logo.svg" alt="" width={44} height={44} />
        <div>
          <div className="brand-name">{t('shop_name')}</div>
          <div className="small muted">{Fmt.date(Date.now())}</div>
        </div>
      </header>

      {status.expiringSoon && (
        <Card tone="error">
          <div className="row gap-sm">
            <span className="text-error"><Icon name="warning" /></span>
            <span>{status.daysLeft === 0 ? t('dash_expiry_today') : t('dash_expiry_warning', Fmt.num(status.daysLeft ?? 0))}</span>
          </div>
          <button className="btn btn-tonal self-start" onClick={() => navigate('/license')}>{t('dash_enter_key')}</button>
        </Card>
      )}

      {!data ? (
        error ? <LoadError error={error} onRetry={reload} /> : <Loading />
      ) : (
        <>
          <Card tone="primary">
            <div className="label">{t('dash_today_sales')}</div>
            <div className="stat-big text-primary">{Fmt.taka(data.todaySalesPoisha)}</div>
          </Card>
          <div className="grid-2">
            <Stat label={t('dash_total_items')} value={Fmt.num(data.stats.totalItems)} onClick={() => navigate('/stock')} />
            <Stat label={t('dash_stock_value')} value={Fmt.taka(data.stats.stockValuePoisha)} />
            <Stat label={t('dash_low_stock')} value={Fmt.num(data.stats.lowStock)} tone="low" onClick={() => navigate('/reports')} />
            <Stat label={t('dash_out_of_stock')} value={Fmt.num(data.stats.outOfStock)} tone="out" onClick={() => navigate('/reports')} />
          </div>
        </>
      )}

      <div className="grid-2">
        <button className="btn btn-primary btn-lg" onClick={() => navigate('/sell')}>
          <Icon name="cart" /> {t('action_sell')}
        </button>
        <button className="btn btn-tonal btn-lg" onClick={() => navigate('/edit/0')}>
          <Icon name="add" /> {t('action_add_item')}
        </button>
      </div>

      {data && (
        <Card>
          <h3>{t('dash_recent_sales')}</h3>
          {data.recent.length === 0 && <p className="muted">{t('dash_no_sales')}</p>}
          <ul className="list">
            {data.recent.map((s) => (
              <li key={s.id}>
                <button className="list-row" onClick={() => navigate(`/item/${s.itemId}`)}>
                  <span className="grow min0">
                    <span className="ellipsis">{s.itemName ?? '—'}</span>
                    <span className="small muted">{Fmt.qty(s.qtyMilli)} {unitLabel(s.unit)} · {Fmt.dateTime(s.soldAt)}</span>
                  </span>
                  <strong>{Fmt.taka(s.totalPoisha)}</strong>
                </button>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  )
}

function Stat({ label, value, tone, onClick }: { label: string; value: string; tone?: 'low' | 'out'; onClick?: () => void }) {
  return (
    <Card onClick={onClick}>
      <div className="label">{label}</div>
      <div className={`stat ${tone ? 'text-' + tone : ''}`}>{value}</div>
    </Card>
  )
}
