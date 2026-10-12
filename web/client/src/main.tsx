import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { bangla } from './i18n'
import './styles.css'

document.documentElement.lang = bangla ? 'bn' : 'en'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
