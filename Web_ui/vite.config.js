import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import tailwindcss from '@tailwindcss/vite'

const envDir = fileURLToPath(new URL('../docker', import.meta.url))

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, envDir, '')
  const port = Number(env.WEB_DEV_PORT)
  if (command === 'serve'
    && (!env.WEB_DEV_HOST || !env.WEB_DEV_API_TARGET || !Number.isInteger(port))) {
    throw new Error('docker/.env 缺少有效的 WEB_DEV_HOST、WEB_DEV_PORT 或 WEB_DEV_API_TARGET')
  }

  return {
    envDir,
    plugins: [
      vue(),
      vueDevTools(),
      tailwindcss(),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      },
    },
    server: command === 'serve' ? {
      host: env.WEB_DEV_HOST,
      port,
      proxy: {
        '/api': {
          target: env.WEB_DEV_API_TARGET,
          changeOrigin: true,
          configure: (proxy) => {
            proxy.on('proxyRes', (proxyRes) => {
              if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
                proxyRes.headers['cache-control'] = 'no-cache'
                proxyRes.headers['connection'] = 'keep-alive'
              }
            })
          },
        },
      },
    } : undefined,
  }
})
