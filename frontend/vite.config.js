import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Start the local Spring Boot backend on port 8081, as described in its README.
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target: 'http://127.0.0.1:8081', changeOrigin: true },
    },
  },
})
