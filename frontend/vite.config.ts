import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [vue(), Components({ resolvers: [AntDesignVueResolver({ importStyle: false })] })],
  server: { port: 5174, strictPort: true, proxy: { '/api': 'http://127.0.0.1:8080' } },
  build: {
    // The UI library is one large, rarely changing chunk; pages are split by route.
    chunkSizeWarningLimit: 1600,
    rollupOptions: { output: { manualChunks: { antd: ['ant-design-vue', '@ant-design/icons-vue', 'dayjs'] } } },
  },
})
