<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const state = ref('idle')
const response = ref(null)
const error = ref('')
const checkedAt = ref('')
const statusLabel = computed(() => ({
  idle: 'Ready to check',
  checking: 'Connecting to backend',
  connected: 'Backend connected',
  failed: 'Connection needs attention',
})[state.value])

async function checkConnection() {
  if (state.value === 'checking') return
  state.value = 'checking'
  response.value = null
  error.value = ''
  checkedAt.value = ''
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 45000)
  try {
    const result = await fetch('/api/status', {
      signal: controller.signal,
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    })
    if (!result.ok) throw new Error(`The backend returned HTTP ${result.status}.`)
    if (!result.headers.get('content-type')?.includes('application/json')) {
      throw new Error('Expected a JSON response. Check the /api/** routing in firebase.json.')
    }
    const data = await result.json()
    if (data.status !== 'ok' || data.service !== 'shipping-api') {
      throw new Error('The response does not match the shipping-api status endpoint.')
    }
    response.value = data
    state.value = 'connected'
    checkedAt.value = new Date().toLocaleTimeString()
  } catch (cause) {
    state.value = 'failed'
    error.value = cause.name === 'AbortError'
      ? 'The request timed out. The backend may be starting; try again.'
      : cause.message || 'Unable to reach the backend. Check your connection and retry.'
  } finally {
    clearTimeout(timeout)
  }
}

function navigateToInbox() {
  router.push('/inbox')
}

onMounted(checkConnection)
</script>

<template>
  <main>
    <p class="eyebrow">YOUR FIRST CLOUD CONNECTION</p>
    <h1>A clear start for<br />every shipment.</h1>
    <p class="intro">Your Vue frontend is running. Check that it can reach your Java backend before building the document verification workflow.</p>

    <section class="connection-card" aria-labelledby="connection-title">
      <div class="card-top">
        <div>
          <p class="eyebrow">CONNECTION CHECK</p>
          <h2 id="connection-title">Frontend to backend</h2>
        </div>
        <span class="endpoint">/api/status</span>
      </div>

      <div class="status" :class="state" role="status" aria-live="polite">
        <span class="status-dot" aria-hidden="true"></span>
        <strong>{{ statusLabel }}</strong>
      </div>

      <p v-if="state === 'checking'" class="help">The first request can take a moment while Cloud Run starts.</p>
      <p v-else-if="state === 'connected'" class="help">Your frontend received a live response from Spring Boot. Last checked at {{ checkedAt }}.</p>
      <p v-else-if="state === 'failed'" class="error">{{ error }}</p>

      <pre v-if="response" aria-label="Backend response">{{ JSON.stringify(response, null, 2) }}</pre>

      <div class="card-bottom">
        <button type="button" :disabled="state === 'checking'" @click="checkConnection">
          {{ state === 'checking' ? 'Checking…' : 'Check connection' }}
          <span aria-hidden="true">↗</span>
        </button>
        <span class="note">A live check, every time.</span>
      </div>
    </section>

    <div class="next-step-action">
      <button type="button" class="btn-redirect" @click="navigateToInbox">
        <div class="btn-redirect-text">
          <span class="btn-badge">Step 02</span>
          <strong>Open Email & Document Verification Inbox</strong>
          <span class="btn-desc">Review incoming emails, extract SI/BL fields, and verify shipping documents</span>
        </div>
        <span class="btn-arrow" aria-hidden="true">→</span>
      </button>
    </div>
  </main>
</template>
