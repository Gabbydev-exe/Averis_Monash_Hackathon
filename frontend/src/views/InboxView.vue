<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

const searchQuery = ref('')
const selectedFilter = ref('all')
const selectedCategory = ref('ALL')
const categories = { ALL: 'All categories', UNCLASSIFIED: 'Unclassified', BL_COMPARISON: 'SI / BL comparison', SI_REQUEST: 'SI request', INVOICE_QUERY: 'Invoice query', GENERAL: 'General', SPAM: 'Spam' }
const statusLabels = { pending: 'Unprocessed', needs_review: 'Human review required', discrepancy: 'Flagged by reviewer', verified: 'Verified', classified: 'Classified · no comparison needed' }
const AUTO_PROCESSING_PREFERENCE = 'shipping-auto-processing-enabled'
const autoEnabled = ref(localStorage.getItem(AUTO_PROCESSING_PREFERENCE) !== 'false')
const automationMessage = ref('Automatic verification is enabled.')
const automationBusy = ref(false)
const queueErrors = ref([])
const forceReverifyBusy = ref(false)
const forceReverifyCancelled = ref(false)
const forceProgress = ref(null)
let automationTimer
let disposed = false
watch(autoEnabled, enabled => localStorage.setItem(AUTO_PROCESSING_PREFERENCE, String(enabled)))
const categoryCount = key => key === 'ALL' ? emails.value.length : emails.value.filter(email => email.category === key).length
async function automationStep() {
  if (disposed) return
  if (autoEnabled.value && !automationBusy.value && !isVerifying.value) {
    automationBusy.value = true
    automationMessage.value = 'Checking the queue / processing an email…'
    try {
      const response = await fetch('/api/gemini/process-next', { method: 'POST' })
      if (!response.ok) throw new Error(`Automatic processing unavailable (HTTP ${response.status}). Check migration and backend configuration.`)
      const result = await response.json()
      automationMessage.value = result.state === 'processed' ? `Processed ${result.emailId}. Results saved.` : result.message
      const list = await fetch('/api/emails')
      if (list.ok) emails.value = await list.json()
      const failures = await fetch('/api/gemini/queue-errors')
      if (failures.ok) queueErrors.value = await failures.json()
      if (result.emailId === selectedEmailId.value && result.state === 'processed') {
        if (!reviewNote.value.trim()) await selectEmailById(result.emailId)
        else reviewError.value = 'New extraction saved. Refresh this email before submitting your review.'
      }
    } catch (error) { automationMessage.value = error.message; autoEnabled.value = false }
    finally { automationBusy.value = false }
  }
  if (!disposed) automationTimer = setTimeout(automationStep, 5000)
}
const selectedEmailId = ref(null)
const isVerifying = ref(false)
const workflow = ref(null)
const reviewError = ref('')
const reviewer = ref('')
const reviewNote = ref('')
const mobileView = ref('list') // 'list' | 'detail'

const emails = ref([])
const currentDetail = ref(null)
const isLoadingList = ref(true)
const isLoadingDetail = ref(false)
const errorMessage = ref('')

async function errorFrom(response, fallback) {
  const data = await response.json().catch(() => ({}))
  return data.message || data.detail || `${fallback} (HTTP ${response.status}).`
}

async function fetchEmailList() {
  isLoadingList.value = true
  errorMessage.value = ''
  try {
    const res = await fetch('/api/emails')
    if (!res.ok) {
      throw new Error(`Failed to load emails: HTTP ${res.status}`)
    }
    const data = await res.json()
    emails.value = data
    if (data.length > 0) {
      const initialId = selectedEmailId.value || data[0].id
      await selectEmailById(initialId)
    }
  } catch (err) {
    errorMessage.value = err.message || 'Unable to connect to backend service.'
  } finally {
    isLoadingList.value = false
  }
}

async function selectEmailById(id) {
  selectedEmailId.value = id
  currentDetail.value = null
  workflow.value = null
  reviewError.value = ''
  reviewNote.value = ''
  const summary = emails.value.find(e => e.id === id)
  if (summary) {
    summary.unread = false
  }
  isLoadingDetail.value = true
  try {
    const res = await fetch(`/api/emails/${id}`)
    if (!res.ok) throw new Error(await errorFrom(res, 'Could not load email'))
    if (res.ok) {
      const detail = await res.json()
      if (selectedEmailId.value !== id) return
      currentDetail.value = detail
      const saved = await fetch(`/api/emails/${id}/workflow`)
      if (!saved.ok) throw new Error(await errorFrom(saved, 'Could not load saved verification and reviews'))
      const data = await saved.json()
      if (selectedEmailId.value === id) workflow.value = data
    }
  } catch (err) {
    reviewError.value = err.message || 'Could not load email details.'
  } finally {
    isLoadingDetail.value = false
  }
}

function selectEmail(email) {
  mobileView.value = 'detail'
  selectEmailById(email.id)
}

const stats = computed(() => {
  const all = emails.value.length
  const verified = emails.value.filter(e => e.status === 'verified').length
  const discrepancy = emails.value.filter(e => e.status === 'discrepancy').length
  const pending = emails.value.filter(e => e.status === 'pending').length
  return { all, verified, discrepancy, pending }
})

const filteredEmails = computed(() => {
  return emails.value.filter(email => {
    const matchesFilter = (selectedFilter.value === 'all' || email.status === selectedFilter.value) && (selectedCategory.value === 'ALL' || email.category === selectedCategory.value)
    const query = searchQuery.value.trim().toLowerCase()
    if (!query) return matchesFilter
    const matchesSearch =
      (email.subject && email.subject.toLowerCase().includes(query)) ||
      (email.sender && email.sender.toLowerCase().includes(query)) ||
      (email.senderName && email.senderName.toLowerCase().includes(query)) ||
      (email.bookingNo && email.bookingNo.toLowerCase().includes(query)) ||
      (email.id && email.id.toLowerCase().includes(query))
    return matchesFilter && matchesSearch
  })
})

async function runVerification() {
  if (!currentDetail.value || isVerifying.value) return
  const id = currentDetail.value.id
  isVerifying.value = true
  reviewError.value = ''
  try {
    const response = await fetch(`/api/gemini/process/${id}`, { method: 'POST' })
    if (!response.ok) {
      const error = await response.json().catch(() => ({}))
      throw new Error(error.message || error.detail || 'AI processing failed. Please retry.')
    }
    const saved = await response.json()
    const item = emails.value.find(email => email.id === id)
    if (item) { item.status = saved.workflow_status; item.category = saved.category }
    if (selectedEmailId.value === id) await selectEmailById(id)
  } catch (error) { reviewError.value = error.message }
  finally { isVerifying.value = false }
}

async function forceReverifyAll() {
  if (forceReverifyBusy.value || automationBusy.value || isVerifying.value || emails.value.length === 0) return

  const confirmed = window.confirm(
    `Force Gemini to re-verify all ${emails.value.length} emails?\n\n` +
    'This can generate hundreds of paid Vertex AI requests. Each successful result replaces the current AI extraction; saved human reviews remain in history. Keep this page open until processing finishes.'
  )
  if (!confirmed) return

  // Avoid competing with the normal queue while this browser explicitly reprocesses every email.
  autoEnabled.value = false
  forceReverifyBusy.value = true
  forceReverifyCancelled.value = false
  isVerifying.value = true
  reviewError.value = ''

  const ids = emails.value.map(email => email.id)
  let attempted = 0
  let succeeded = 0
  let failed = 0
  let consecutiveFailures = 0
  let lastFailure = ''

  forceProgress.value = { attempted, total: ids.length, succeeded, failed, state: 'running' }

  try {
    for (const id of ids) {
      if (forceReverifyCancelled.value) break

      try {
        const response = await fetch(`/api/gemini/process/${id}`, { method: 'POST' })
        if (!response.ok) throw new Error(await errorFrom(response, `Could not re-verify ${id}`))

        const saved = await response.json()
        const item = emails.value.find(email => email.id === id)
        if (item) { item.status = saved.workflow_status; item.category = saved.category }
        succeeded++
        consecutiveFailures = 0
      } catch (error) {
        failed++
        consecutiveFailures++
        lastFailure = `${id}: ${error.message || 'AI processing failed.'}`

        // A shared IAM, quota or model outage should not create hundreds of failed paid requests.
        if (consecutiveFailures >= 3) {
          forceReverifyCancelled.value = true
          break
        }
      } finally {
        attempted++
        forceProgress.value = { attempted, total: ids.length, succeeded, failed, state: 'running' }
      }

      // Keep the bulk action sequential and leave a small gap between model workloads.
      await new Promise(resolve => setTimeout(resolve, 1000))
    }
  } finally {
    const stoppedForFailures = consecutiveFailures >= 3
    const cancelled = forceReverifyCancelled.value && !stoppedForFailures
    forceProgress.value = {
      attempted,
      total: ids.length,
      succeeded,
      failed,
      state: stoppedForFailures ? 'failed' : cancelled ? 'cancelled' : 'complete',
    }
    automationMessage.value = stoppedForFailures
      ? `Bulk re-verification stopped after three consecutive failures. ${lastFailure}`
      : cancelled
        ? `Bulk re-verification cancelled after ${attempted} emails.`
        : `Bulk re-verification finished: ${succeeded} succeeded and ${failed} failed.`

    forceReverifyBusy.value = false
    isVerifying.value = false
    await fetchEmailList()
    const failures = await fetch('/api/gemini/queue-errors')
    if (failures.ok) queueErrors.value = await failures.json()
  }
}

function cancelForceReverify() {
  forceReverifyCancelled.value = true
  automationMessage.value = 'Stopping bulk re-verification after the current email finishes…'
}

async function saveReview(decision) {
  if (!currentDetail.value || !workflow.value || isVerifying.value) return
  const id = currentDetail.value.id
  isVerifying.value = true
  reviewError.value = ''
  try {
    const response = await fetch(`/api/emails/${id}/reviews`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ revision: workflow.value.revision, decision, reviewer: reviewer.value, note: reviewNote.value }),
    })
    if (!response.ok) {
      const error = await response.json().catch(() => ({}))
      throw new Error(error.message || 'Review was not saved. Please retry.')
    }
    const saved = await response.json()
    const item = emails.value.find(email => email.id === id)
    if (item) item.status = saved.status
    if (selectedEmailId.value === id) {
      workflow.value = saved
      currentDetail.value.status = saved.status
      reviewNote.value = ''
    }
  } catch (error) { reviewError.value = error.message }
  finally { isVerifying.value = false }
}

onMounted(() => {
  fetchEmailList()
  automationTimer = setTimeout(automationStep, 1500)
})
onUnmounted(() => { disposed = true; clearTimeout(automationTimer) })
</script>

<template>
  <div class="inbox-page">
    <!-- Top Navigation Bar -->
    <div class="inbox-header">
      <div class="inbox-title-group">
        <h1>Shipping Document Verification Inbox</h1>
        <p class="inbox-subtitle">
          Cross-examine incoming carrier emails and verify Shipping Instructions (SI) against draft Bills of Lading (B/L).
        </p>
      </div>

      <div class="inbox-stats">
        <div class="stat-badge stat-all">
          <span class="stat-num">{{ stats.all }}</span>
          <span class="stat-lbl">Total Emails</span>
        </div>
        <div class="stat-badge stat-pending">
          <span class="stat-num">{{ stats.pending }}</span>
          <span class="stat-lbl">Unprocessed</span>
        </div>
        <div class="stat-badge stat-discrepancy">
          <span class="stat-num">{{ stats.discrepancy }}</span>
          <span class="stat-lbl">Discrepancies</span>
        </div>
        <div class="stat-badge stat-verified">
          <span class="stat-num">{{ stats.verified }}</span>
          <span class="stat-lbl">Verified</span>
        </div>
      </div>
    </div>

    <section class="automation-bar" aria-label="Automatic verification">
      <label><input v-model="autoEnabled" type="checkbox" :disabled="forceReverifyBusy" /> Automatically process unverified emails</label>
      <p role="status">{{ automationMessage }} <span v-if="!autoEnabled && automationBusy">Current request will finish.</span></p>
      <small>This browser setting is remembered after reload. Exact seven-field matches pass automatically. Every difference requires human review. Similarity percentages are advisory.</small>
      <div class="automation-danger-zone">
        <button
          type="button"
          class="btn-force-reverify"
          :disabled="forceReverifyBusy || automationBusy || isVerifying || emails.length === 0"
          @click="forceReverifyAll"
        >
          {{ forceReverifyBusy ? 'Re-verifying all emails…' : '⚠ Force AI to re-verify all emails' }}
        </button>
        <button v-if="forceReverifyBusy" type="button" class="btn-cancel-reverify" @click="cancelForceReverify">Stop after current email</button>
        <p v-if="forceProgress" class="force-progress" role="status">
          {{ forceProgress.attempted }} / {{ forceProgress.total }} attempted ·
          {{ forceProgress.succeeded }} succeeded · {{ forceProgress.failed }} failed
        </p>
      </div>
      <details v-if="queueErrors.length"><summary>Processing errors ({{ queueErrors.length }})</summary><p v-for="failure in queueErrors" :key="failure.email_id">{{ failure.email_id }} · {{ failure.attempts }} attempts · {{ failure.last_error }}</p></details>
    </section>
    <nav class="category-tabs" aria-label="Email categories">
      <button v-for="(label, key) in categories" :key="key" type="button" :aria-pressed="selectedCategory === key" :class="{ active: selectedCategory === key }" @click="selectedCategory = key">{{ label }} ({{ categoryCount(key) }})</button>
    </nav>

    <!-- Error Banner -->
    <div v-if="errorMessage" class="error-banner" role="alert">
      <span>⚠️ {{ errorMessage }}</span>
      <button type="button" class="btn-retry" @click="fetchEmailList">Retry</button>
    </div>

    <!-- Mobile View Switcher (Tabs visible on mobile/tablet screens) -->
    <div class="mobile-view-tabs" role="tablist" aria-label="Mobile View Selector">
      <button
        type="button"
        :class="['mobile-tab-btn', { active: mobileView === 'list' }]"
        role="tab"
        :aria-selected="mobileView === 'list'"
        @click="mobileView = 'list'"
      >
        📬 Emails ({{ filteredEmails.length }})
      </button>
      <button
        type="button"
        :class="['mobile-tab-btn', { active: mobileView === 'detail' }]"
        role="tab"
        :aria-selected="mobileView === 'detail'"
        @click="mobileView = 'detail'"
      >
        📄 Document Details
      </button>
    </div>

    <!-- Main Workspace: Inbox List + Detail Verification Panel -->
    <div class="inbox-layout">
      <!-- Left: Email List -->
      <section
        :class="['inbox-sidebar', { 'is-mobile-active': mobileView === 'list', 'is-mobile-hidden': mobileView !== 'list' }]"
        aria-label="Email list"
      >
        <div class="sidebar-controls">
          <input
            v-model="searchQuery"
            type="search"
            class="search-input"
            placeholder="Search booking #, email, subject..."
            aria-label="Search emails"
          />

          <div class="filter-pills" role="tablist" aria-label="Email filters">
            <button
              type="button"
              :class="['filter-pill', { active: selectedFilter === 'all' }]"
              @click="selectedFilter = 'all'"
            >
              All ({{ stats.all }})
            </button>
            <button
              type="button"
              :class="['filter-pill', { active: selectedFilter === 'pending' }]"
              @click="selectedFilter = 'pending'"
            >
              Unprocessed ({{ stats.pending }})
            </button>
            <button
              type="button"
              :class="['filter-pill', { active: selectedFilter === 'discrepancy' }]"
              @click="selectedFilter = 'discrepancy'"
            >
              Discrepancies ({{ stats.discrepancy }})
            </button>
            <button
              type="button"
              :class="['filter-pill', { active: selectedFilter === 'verified' }]"
              @click="selectedFilter = 'verified'"
            >
              Verified ({{ stats.verified }})
            </button>
            <button type="button" :class="['filter-pill', { active: selectedFilter === 'needs_review' }]" @click="selectedFilter = 'needs_review'">Human review ({{ emails.filter(email => email.status === 'needs_review').length }})</button>
            <button type="button" :class="['filter-pill', { active: selectedFilter === 'classified' }]" @click="selectedFilter = 'classified'">Classified</button>
          </div>
        </div>

        <div v-if="isLoadingList" class="loading-state">
          <div class="spinner"></div>
          <span>Loading dataset emails…</span>
        </div>

        <ul v-else class="email-list" role="list">
          <li
            v-for="email in filteredEmails"
            :key="email.id"
            :class="[
              'email-item',
              { active: selectedEmailId === email.id, unread: email.unread }
            ]"
            @click="selectEmail(email)"
          >
            <div class="email-item-header">
              <span class="sender-name">{{ email.senderName }}</span>
              <span class="email-date">{{ email.date }}</span>
            </div>
            <div class="email-subject">{{ email.subject }}</div>
            <small class="email-category">{{ categories[email.category] || email.category }}</small>
            <div class="email-meta">
              <span class="booking-tag">{{ email.bookingNo }}</span>
              <span :class="['status-pill', email.status]">
                {{ statusLabels[email.status] || email.status }}
              </span>
            </div>
          </li>
          <li v-if="filteredEmails.length === 0" class="no-emails">
            No emails found matching the selected filter.
          </li>
        </ul>
      </section>

      <!-- Right: Document Verification Detail -->
      <section
        v-if="currentDetail"
        :class="['inbox-detail', { 'is-mobile-active': mobileView === 'detail', 'is-mobile-hidden': mobileView !== 'detail' }]"
        aria-label="Email and Verification Detail"
      >
        <!-- Mobile Back Button -->
        <button
          type="button"
          class="mobile-back-btn"
          @click="mobileView = 'list'"
          aria-label="Back to Email List"
        >
          ← Back to Email List
        </button>

        <!-- Email Header Banner -->
        <div class="detail-header">
          <div class="detail-title-section">
            <div class="detail-tags">
              <span class="booking-tag-lg">Booking: {{ currentDetail.bookingNo }}</span>
              <span :class="['status-pill-lg', currentDetail.status]">
                {{ statusLabels[currentDetail.status] || currentDetail.status }}
              </span>
            </div>
            <h2>{{ currentDetail.subject }}</h2>
            <p><strong>Category:</strong> {{ categories[workflow?.category] || 'Loading…' }}</p>
            <div class="sender-info">
              <strong>From:</strong> {{ currentDetail.senderName }} &lt;{{ currentDetail.sender }}&gt; • <span>{{ currentDetail.date }}</span>
            </div>
          </div>

          <div class="detail-actions">
            <button
              type="button"
              class="btn-verify"
              :disabled="isVerifying || isLoadingDetail"
              @click="runVerification"
            >
              {{ isVerifying ? 'Processing…' : 'Run Verification' }}
            </button>

            <div v-if="workflow?.category === 'BL_COMPARISON'" class="flag-approve-btn-group">
              <button
                type="button"
                class="btn-approve"
                :disabled="isVerifying || !workflow || !reviewer.trim() || !reviewNote.trim() || !workflow.assessment || workflow.assessment.status === 'NEEDS_REVIEW' || currentDetail.fields.some(field => field.status === 'pending')"
                @click="saveReview('APPROVE')"
              >
                ✓ Approve
              </button>
              <button
                type="button"
                class="btn-flag"
                :disabled="isVerifying || !workflow || !reviewer.trim() || !reviewNote.trim()"
                @click="saveReview('FLAG')"
              >
                ⚠ Flag Issue
              </button>
            </div>
          </div>
        </div>

        <p v-if="reviewError" class="error-banner" role="alert">{{ reviewError }}</p>
        <section v-if="workflow?.category === 'BL_COMPARISON'" class="review-persistence" aria-label="Human review">
          <p v-if="isVerifying" role="status">Processing request…</p>
          <label>Your name (self-reported)<input v-model="reviewer" maxlength="200" placeholder="Reviewer name" /></label>
          <label>Review note<textarea v-model="reviewNote" maxlength="4000" placeholder="Explain your approval or flag" /></label>
          <p>Decisions are saved to the database. Approval requires all seven SI and BL values. Human decisions do not change extracted evidence.</p>
          <details v-if="workflow?.reviews.length">
            <summary>Saved review history ({{ workflow.reviews.length }})</summary>
            <p v-for="review in workflow.reviews" :key="review.id"><strong>{{ review.decision }} · {{ review.reviewer }}</strong> · {{ review.savedAt }}<br />{{ review.note }}<br /><small>{{ review.revision === workflow.revision ? 'Current extraction' : 'Previous extraction' }}</small></p>
          </details>
        </section>

        <!-- Document Attachments Bar -->
        <div class="attachments-card">
          <div class="attachments-title">
            <span>📎 Attached Shipping Documents ({{ currentDetail.attachments ? currentDetail.attachments.length : 0 }})</span>
            <span class="ocr-status">Ready for extraction</span>
          </div>
          <div class="attachments-list">
            <a
              v-for="(att, i) in currentDetail.attachments"
              :key="i"
              :href="'/api/emails/' + currentDetail.id + '/attachments/' + encodeURIComponent(att.name)"
              target="_blank"
              class="attachment-chip attachment-link"
              :title="'Click to view or download ' + encodeURIComponent(att.name)"
            >
              <span class="att-type" :class="att.type">{{ att.type }}</span>
              <span class="att-name">{{ att.name }}</span>
              <span class="att-size">{{ att.size }}</span>
            </a>
          </div>
        </div>

        <section v-if="workflow?.assessment" class="assessment-card" aria-label="Saved AI assessment">
          <h3>{{ statusLabels[currentDetail.status] }}</h3>
          <p>{{ workflow.assessment.explanation }}</p>
          <p v-if="workflow.assessment.reviewReason"><strong>Review reason:</strong> {{ workflow.assessment.reviewReason }}</p>
          <p v-if="workflow.category === 'BL_COMPARISON'"><strong>{{ workflow.assessment.status === 'OK' ? 'Exact field match' : 'AI-estimated match' }}:</strong> {{ workflow.assessment.matchPercentage == null ? 'Unavailable' : workflow.assessment.matchPercentage + '%' }}</p>
          <p v-if="workflow.assessment.status === 'MISMATCH'">Different fields: {{ workflow.assessment.defectFields.join(', ') }}. A high percentage does not mean approval.</p>
        </section>
        <p v-else class="assessment-card">Waiting for automatic processing. You can also use Run Verification.</p>

        <section v-if="workflow?.category === 'SI_REQUEST'" class="assessment-card">
          <h3>New shipping instruction</h3>
          <p>No draft BL comparison is required yet. Extracted SI values are saved below.</p>
          <dl v-for="field in workflow.fields" :key="field.key"><dt>{{ field.key }}</dt><dd>{{ field.si || 'Not supplied' }} <small v-if="field.siEvidence">({{ field.siEvidence }})</small></dd></dl>
        </section>

        <!-- Verification Table: SI vs BL Comparison -->
        <div v-if="workflow?.category === 'BL_COMPARISON'" class="verification-section">
          <div class="verification-header">
            <div>
              <h3>Shipping Instruction (SI) vs. Draft Bill of Lading (B/L)</h3>
              <p class="section-desc">Exact field comparison. Normalized similarity does not authorize automatic approval.</p>
            </div>

          </div>

          <div class="table-scroll-hint" aria-hidden="true">
            <span>↔ Swipe horizontally to view full SI vs B/L comparison</span>
          </div>

          <div class="table-container">
            <table class="verification-table">
              <thead>
                <tr>
                  <th style="width: 20%;">Document Field</th>
                  <th style="width: 32%;">Shipping Instruction (SI)</th>
                  <th style="width: 32%;">Draft Bill of Lading (B/L)</th>
                  <th style="width: 16%;">Verification Status</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(field, index) in currentDetail.fields"
                  :key="index"
                  :class="['field-row', field.status]"
                >
                  <td class="field-label">
                    <strong>{{ field.label }}</strong>
                  </td>
                  <td class="field-value si-val">
                    {{ field.si }}
                  </td>
                  <td class="field-value bl-val">
                    {{ field.bl }}
                    <div v-if="field.note" :class="['evidence-note', field.status]">
                      {{ field.note }}
                    </div>
                  </td>
                  <td class="field-status-cell">
                    <span :class="['field-badge', field.status]">
                      <span v-if="field.status === 'match'">✓ Match</span>
                      <span v-else-if="field.status === 'mismatch'">✗ Discrepancy</span>
                      <span v-else>⏳ Pending</span>
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Original Email Body Preview -->
        <div class="email-body-card">
          <h4>Original Email Message</h4>
          <p class="email-text">{{ currentDetail.bodyText }}</p>
        </div>
      </section>

      <section v-else class="inbox-detail empty-detail">
        <p v-if="isLoadingDetail" role="status">Loading email details…</p>
        <template v-else-if="reviewError">
          <p class="detail-load-error" role="alert">{{ reviewError }}</p>
          <p>Check the database migrations, then retry this email.</p>
          <button type="button" class="btn-retry" :disabled="!selectedEmailId" @click="selectEmailById(selectedEmailId)">Retry</button>
        </template>
        <p v-else>Select an email from the list to inspect document details.</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.automation-bar, .assessment-card { padding: 16px; background: #f0f5ed; border: 1px solid #ccdcca; border-radius: 12px; margin-bottom: 16px; overflow-wrap: anywhere; }
.automation-danger-zone { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 14px; padding-top: 14px; border-top: 1px solid #d7b5b5; }
.btn-force-reverify { padding: 9px 14px; border: 1px solid #991b1b; border-radius: 7px; background: #dc2626; color: #fff; font-weight: 700; cursor: pointer; }
.btn-force-reverify:hover:not(:disabled) { background: #b91c1c; }
.btn-force-reverify:disabled { opacity: 0.58; cursor: not-allowed; }
.btn-cancel-reverify { padding: 8px 12px; border: 1px solid #b91c1c; border-radius: 7px; background: #fff; color: #991b1b; font-weight: 600; cursor: pointer; }
.force-progress { flex-basis: 100%; margin: 0; color: #7f1d1d; font-size: 13px; font-weight: 600; }
.category-tabs { display: flex; flex-wrap: wrap; gap: 8px; margin: 16px 0; }
.category-tabs button { padding: 9px 12px; background: white; color: #164f45; border: 1px solid #b7c9bd; }
.category-tabs button.active { background: #164f45; color: white; }

.review-persistence { margin: 16px 0; padding: 16px; border: 1px solid #dce4de; border-radius: 12px; font-size: 13px; }
.review-persistence label { display: block; margin: 10px 0; }
.review-persistence input, .review-persistence textarea { display: block; box-sizing: border-box; width: 100%; margin-top: 6px; padding: 10px; border: 1px solid #b7c9bd; border-radius: 6px; font: inherit; }
.review-persistence p { line-height: 1.6; overflow-wrap: anywhere; }
.review-persistence [role=alert] { color: #a23e32; }

.inbox-page {
  max-width: 1400px;
  margin: 0 auto;
  padding: 24px 20px 48px;
  font-family: inherit;
  color: #1e293b;
}

.error-banner {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #991b1b;
  padding: 12px 16px;
  border-radius: 8px;
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.detail-load-error { color: #943f31; font-weight: 600; }

.btn-retry {
  background: #dc2626;
  color: white;
  border: none;
  padding: 6px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: #64748b;
  gap: 12px;
}

.spinner {
  width: 24px;
  height: 24px;
  border: 3px solid #e2e8f0;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.attachment-link {
  text-decoration: none;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.attachment-link:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 4px rgba(0,0,0,0.06);
}

.inbox-header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  margin-bottom: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid #e2e8f0;
}

.inbox-title-group h1 {
  font-size: 26px;
  font-weight: 700;
  color: #0f172a;
  margin: 6px 0 4px;
}

.inbox-subtitle {
  color: #64748b;
  font-size: 14px;
  margin: 0;
}

.back-link {
  background: none;
  border: none;
  color: #2563eb;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  padding: 0;
  margin-bottom: 4px;
}

.back-link:hover {
  text-decoration: underline;
}

.inbox-stats {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.stat-badge {
  display: flex;
  flex-direction: column;
  padding: 8px 14px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  min-width: 90px;
}

.stat-num {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
}

.stat-lbl {
  font-size: 11px;
  color: #64748b;
  text-transform: uppercase;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.stat-pending .stat-num { color: #d97706; }
.stat-discrepancy .stat-num { color: #dc2626; }
.stat-verified .stat-num { color: #16a34a; }

.mobile-view-tabs {
  display: none;
  gap: 8px;
  margin-bottom: 16px;
}

.mobile-tab-btn {
  flex: 1;
  padding: 10px 14px;
  font-size: 14px;
  font-weight: 600;
  border: 1px solid #cbd5e1;
  background: #f8fafc;
  color: #475569;
  border-radius: 8px;
  cursor: pointer;
}

.mobile-tab-btn.active {
  background: #0f172a;
  color: #ffffff;
  border-color: #0f172a;
}

.mobile-back-btn {
  display: none;
  background: #f1f5f9;
  border: 1px solid #cbd5e1;
  color: #1e293b;
  padding: 8px 14px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  margin-bottom: 16px;
  width: fit-content;
}

.inbox-layout {
  display: grid;
  grid-template-columns: 380px 1fr;
  gap: 24px;
  align-items: start;
}

.inbox-sidebar {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05);
}

.sidebar-controls {
  padding: 16px;
  border-bottom: 1px solid #e2e8f0;
  background: #fafafa;
}

.search-input {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 14px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 12px;
  outline: none;
}

.search-input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37,99,235,0.15);
}

.filter-pills {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.filter-pill {
  padding: 5px 10px;
  font-size: 12px;
  font-weight: 500;
  border-radius: 20px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #64748b;
  cursor: pointer;
}

.filter-pill.active {
  background: #2563eb;
  color: #ffffff;
  border-color: #2563eb;
}

.email-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 720px;
  overflow-y: auto;
}

.email-item {
  padding: 14px 16px;
  border-bottom: 1px solid #f1f5f9;
  cursor: pointer;
  transition: background 0.15s ease;
}

.email-item:hover {
  background: #f8fafc;
}

.email-item.active {
  background: #eff6ff;
  border-left: 4px solid #2563eb;
}

.email-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.sender-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}

.email-date {
  font-size: 11px;
  color: #94a3b8;
}

.email-subject {
  font-size: 13px;
  color: #475569;
  margin-bottom: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.email-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.booking-tag {
  font-size: 11px;
  font-family: monospace;
  background: #e2e8f0;
  color: #334155;
  padding: 2px 6px;
  border-radius: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 170px;
}

.status-pill {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 12px;
}

.status-pill.pending { background: #fef3c7; color: #92400e; }
.status-pill.discrepancy { background: #fee2e2; color: #991b1b; }
.status-pill.verified { background: #dcfce7; color: #166534; }

.no-emails {
  padding: 24px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
}

.inbox-detail {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.05);
}

.empty-detail {
  text-align: center;
  color: #94a3b8;
  padding: 60px 20px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid #e2e8f0;
}

.detail-tags {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.booking-tag-lg {
  font-size: 12px;
  font-family: monospace;
  background: #f1f5f9;
  color: #334155;
  padding: 4px 8px;
  border-radius: 6px;
  font-weight: 600;
}

.status-pill-lg {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 14px;
}

.status-pill-lg.pending { background: #fef3c7; color: #92400e; }
.status-pill-lg.discrepancy { background: #fee2e2; color: #991b1b; }
.status-pill-lg.verified { background: #dcfce7; color: #166534; }

.detail-title-section h2 {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 6px;
}

.sender-info {
  font-size: 13px;
  color: #64748b;
}

.detail-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-end;
}

.btn-verify {
  background: #2563eb;
  color: #ffffff;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.btn-verify:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.flag-approve-btn-group {
  display: flex;
  gap: 6px;
}

.btn-approve {
  background: #16a34a;
  color: #ffffff;
  border: none;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.btn-flag {
  background: #dc2626;
  color: #ffffff;
  border: none;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.attachments-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 24px;
}

.attachments-title {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 10px;
}

.ocr-status {
  font-size: 11px;
  color: #059669;
  font-weight: 500;
}

.attachments-list {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.attachment-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
}

.att-type {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 4px;
  border-radius: 4px;
  background: #e2e8f0;
  color: #334155;
}

.att-type.SI { background: #dbeafe; color: #1e40af; }
.att-type.BL { background: #fce7f3; color: #9d174d; }

.att-name {
  font-weight: 500;
  color: #1e293b;
}

.att-size {
  color: #94a3b8;
  font-size: 11px;
}

.verification-section {
  margin-bottom: 24px;
}

.verification-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 12px;
}

.verification-header h3 {
  font-size: 15px;
  font-weight: 700;
  margin: 0 0 2px;
  color: #0f172a;
}

.section-desc {
  font-size: 12px;
  color: #64748b;
  margin: 0;
}

.vessel-badge {
  font-size: 12px;
  color: #334155;
}

.table-scroll-hint {
  display: none;
  font-size: 11px;
  color: #64748b;
  margin-bottom: 6px;
}

.table-container {
  overflow-x: auto;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.verification-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.verification-table th {
  background: #f8fafc;
  padding: 10px 14px;
  text-align: left;
  font-weight: 600;
  color: #475569;
  border-bottom: 1px solid #e2e8f0;
}

.verification-table td {
  padding: 12px 14px;
  border-bottom: 1px solid #f1f5f9;
  vertical-align: top;
}

.field-row.mismatch {
  background: #fef2f2;
}

.field-label {
  color: #334155;
}

.si-val {
  color: #1e293b;
}

.bl-val {
  color: #1e293b;
}

.evidence-note {
  margin-top: 4px;
  font-size: 11px;
  font-weight: 500;
}

.evidence-note.match { color: #111827; }
.evidence-note.mismatch { color: #dc2626; }
.evidence-note.pending { color: #92400e; }

.field-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
  display: inline-block;
}

.field-badge.pending { background: #fef3c7; color: #92400e; }
.field-badge.match { background: #dcfce7; color: #166534; }
.field-badge.mismatch { background: #fee2e2; color: #991b1b; }

.email-body-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 16px;
}

.email-body-card h4 {
  font-size: 13px;
  font-weight: 700;
  color: #334155;
  margin: 0 0 8px;
}

.email-text {
  font-size: 12px;
  color: #475569;
  white-space: pre-line;
  line-height: 1.5;
  margin: 0;
}

@media (max-width: 900px) {
  .inbox-layout {
    grid-template-columns: 1fr;
  }

  .mobile-view-tabs {
    display: flex;
  }

  .mobile-back-btn {
    display: inline-block;
  }

  .inbox-sidebar.is-mobile-hidden,
  .inbox-detail.is-mobile-hidden {
    display: none;
  }

  .table-scroll-hint {
    display: block;
  }
}
</style>
