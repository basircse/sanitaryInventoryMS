import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev: `npm run dev` on :5173 forwards /api to Spring Boot on :8110 (host: true so phones on the
// same Wi-Fi can open it). Production: `npm run build` → dist/, which Maven packs into the jar.
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    proxy: { '/api': 'http://localhost:8110' },
  },
})
