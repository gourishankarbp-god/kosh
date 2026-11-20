import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
// Served from https://gourishankarbp-god.github.io/kosh/
export default defineConfig({ base: '/kosh/', plugins: [react()], build: { target: 'es2022', chunkSizeWarningLimit: 1500 }, test: { environment: 'node' } } as any)
