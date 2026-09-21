<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const state = ref('checking')
const checkedAt = ref('')
const label = computed(() => ({ checking: 'Checking connection…', connected: 'Backend connected', failed: 'Backend unavailable · retry' })[state.value])
let timer
let controller
async function check() {
  if (controller) return
  state.value = 'checking'
  controller = new AbortController()
  const timeout = setTimeout(() => controller?.abort(), 45000)
  try {
    const response = await fetch('/api/status', { cache: 'no-store', signal: controller.signal })
    if (!response.ok) throw new Error('Unavailable')
    const data = await response.json()
    state.value = data.status === 'ok' && data.service === 'shipping-api' ? 'connected' : 'failed'
    checkedAt.value = new Date().toLocaleTimeString()
  } catch {
    state.value = 'failed'
  } finally {
    clearTimeout(timeout)
    controller = null
  }
}
onMounted(() => { check(); timer = setInterval(check, 60000) })
onUnmounted(() => { clearInterval(timer); controller?.abort() })
</script>

<template>
  <button class="connection-mini" :class="state" type="button" @click="check"
    :aria-label="label" :disabled="state === 'checking'" :title="`API connectivity only. Last checked: ${checkedAt || 'not yet'}. Click to refresh.`">
    <span class="connection-mini-dot" aria-hidden="true"></span>
    <span role="status" aria-live="polite">{{ label }}</span>
  </button>
</template>

<style scoped>
.connection-mini { padding: 4px 0; background: transparent; color: #52685e; font-size: 11px; font-weight: 500; justify-content: flex-start; gap: 6px; }
.connection-mini:hover { background: transparent; color: #164f45; }
.connection-mini-dot { width: 6px; height: 6px; border-radius: 50%; background: #ad822c; }
.connected .connection-mini-dot { background: #22835e; }
.failed { color: #a23e32; }
.failed .connection-mini-dot { background: #ba4939; }
</style>
