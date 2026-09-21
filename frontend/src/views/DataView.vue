<script setup>
import { computed, onMounted, ref } from 'vue'

const records = ref([])
const importPayload = ref('')
const fileNames = ref([])
const error = ref('')
const result = ref(null)
const busy = ref(false)
const preparing = ref(false)
const exporting = ref('')
const fileInput = ref(null)
const emails = ref([])
const senderSearch = ref('')
const selectedIds = ref([])
const loadingEmails = ref(false)
const listError = ref('')
const matchingEmails = computed(() => emails.value.filter(email =>
  `${email.sender} ${email.senderName || ''}`.toLowerCase().includes(senderSearch.value.trim().toLowerCase())))
const matchingSelected = computed(() => matchingEmails.value.filter(email => selectedIds.value.includes(email.id)).length)
const allMatchingSelected = computed(() => matchingEmails.value.length > 0 && matchingSelected.value === matchingEmails.value.length)
const cannotExport = computed(() => !selectedIds.value.length || loadingEmails.value || !!listError.value || !!exporting.value || busy.value)
function selectMatching(event) {
  const ids = new Set(selectedIds.value)
  for (const email of matchingEmails.value) {
    if (event.target.checked) ids.add(email.id)
    else ids.delete(email.id)
  }
  selectedIds.value = [...ids]
}
async function loadEmails() {
  loadingEmails.value = true
  listError.value = ''
  try {
    const response = await fetch('/api/emails', { cache: 'no-store' })
    if (!response.ok) throw new Error(await errorFrom(response))
    emails.value = await response.json()
    const available = new Set(emails.value.map(email => email.id))
    selectedIds.value = selectedIds.value.filter(id => available.has(id))
  } catch (cause) { listError.value = cause.message || 'Could not load emails.' }
  finally { loadingEmails.value = false }
}
onMounted(loadEmails)
const MAX_BYTES = 5 * 1024 * 1024
const example = JSON.stringify({ email_id: 'email_new_001', from: 'shipping@example.com', subject: 'Draft BL for review', body: 'Please review the shipping documents.', attachments: [] }, null, 2)

async function chooseFiles(event) {
  records.value = []
  importPayload.value = ''
  result.value = null
  error.value = ''
  const files = Array.from(event.target.files || [])
  fileNames.value = files.map(file => file.name)
  if (!files.length) return
  preparing.value = true
  try {
    if (files.some(file => !file.name.toLowerCase().endsWith('.json'))) throw new Error('Choose JSON files. Document uploads are a separate workflow.')
    if (files.reduce((sum, file) => sum + file.size, 0) > MAX_BYTES) throw new Error('Choose at most 5 MB of JSON data.')
    const combined = []
    const originals = []
    for (const file of files) {
      let root
      const original = (await file.text()).replace(/^\uFEFF/, '')
      try { root = JSON.parse(original) }
      catch { throw new Error(`${file.name} is not valid JSON.`) }
      originals.push(original)
      const value = root && !Array.isArray(root) && Object.hasOwn(root, 'emails') ? root.emails : root
      if (Array.isArray(value)) combined.push(...value)
      else if (value && typeof value === 'object' && typeof value.email_id === 'string') combined.push(value)
      else throw new Error(`${file.name}: expected an email object, an array, or { "emails": [...] }.`)
    }
    if (!combined.length || combined.length > 2000) throw new Error('Choose between 1 and 2,000 email records.')
    if (combined.some(record => !record || typeof record !== 'object' || Array.isArray(record))) throw new Error('Each email record must be a JSON object.')
    const payload = `{"files":[${originals.join(',')}]}`
    if (new Blob([payload]).size > MAX_BYTES) throw new Error('Combined JSON data exceeds 5 MB.')
    importPayload.value = payload
    records.value = combined
  } catch (cause) { error.value = cause.message }
  finally { preparing.value = false }
}

async function errorFrom(response) {
  const data = await response.json().catch(() => ({}))
  return data.message || data.detail || `Request failed (HTTP ${response.status}).`
}

async function importData() {
  if (!records.value.length || busy.value) return
  busy.value = true
  error.value = ''
  result.value = null
  try {
    const response = await fetch('/api/emails/import', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: importPayload.value,
    })
    if (!response.ok) throw new Error(await errorFrom(response))
    result.value = await response.json()
    records.value = []
    importPayload.value = ''
    fileNames.value = []
    if (fileInput.value) fileInput.value.value = ''
    await loadEmails()
  } catch (cause) {
    error.value = cause instanceof TypeError
      ? 'Connection interrupted. Check the inbox before retrying; existing email IDs are skipped safely.' : cause.message
  } finally { busy.value = false }
}

function download(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function exportData(format) {
  if (cannotExport.value) return
  exporting.value = format
  error.value = ''
  try {
    const response = await fetch(`/api/emails/export?format=${format}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ emailIds: selectedIds.value }),
    })
    if (!response.ok) throw new Error(await errorFrom(response))
    download(await response.blob(), `shipping-emails.${format}`)
  } catch (cause) { error.value = cause.message || 'Could not export data. Please retry.' }
  finally { exporting.value = '' }
}
</script>

<template>
  <section class="data-page" aria-labelledby="data-title">
    <p class="eyebrow">EMAIL DATA</p>
    <h1 id="data-title">Import once. Keep your data portable.</h1>
    <p class="data-intro">Add structured email records directly, or download your saved inbox. This is separate from uploading and reading document files.</p>

    <div v-if="error" class="data-alert data-error" role="alert">{{ error }}</div>
    <div v-if="result" class="data-alert data-success" role="status">
      <strong>{{ result.inserted }} imported · {{ result.skipped }} existing records skipped</strong>
      <p>Saved to the database. Existing emails and verification history were preserved.</p>
      <router-link to="/inbox">View inbox →</router-link>
    </div>

    <div class="data-grid">
      <section class="data-card" aria-labelledby="import-title">
        <span class="data-step">01 / INSERT RECORDS</span>
        <h2 id="import-title">Import JSON</h2>
        <p>Choose one or more JSON files containing a single email, an array of emails, or an <code>emails</code> array.</p>
        <label class="data-file-label" for="json-files">Email JSON files</label>
        <input id="json-files" ref="fileInput" class="data-file" type="file" accept=".json,application/json" multiple :disabled="busy || preparing" @change="chooseFiles" />
        <p class="data-note">Up to 5 MB and 2,000 records per import. Existing IDs are skipped without overwriting. Repeating an ID within one import rejects the batch.</p>
        <div v-if="records.length" class="data-preview" aria-live="polite">
          <strong>{{ records.length }} records selected</strong>
          <p>{{ fileNames.join(', ') }}</p>
          <ul><li v-for="(record, index) in records.slice(0, 3)" :key="index">{{ record.email_id || 'Missing ID' }} — {{ record.subject || 'No subject' }}</li></ul>
          <p v-if="records.length > 3">And {{ records.length - 3 }} more…</p>
        </div>
        <button type="button" :disabled="!records.length || busy || preparing || !!exporting" @click="importData">
          {{ busy ? 'Importing…' : preparing ? 'Reading files…' : 'Import email data' }}
        </button>
        <p class="data-note">Missing required fields or invalid values reject the whole batch. Blank subject/body text and empty attachment lists are allowed; the sender must not be blank. Attachment paths are references; files are not uploaded and AI verification is not started.</p>
      </section>

      <section class="data-card" aria-labelledby="export-title">
        <span class="data-step">02 / DOWNLOAD RECORDS</span>
        <h2 id="export-title">Export inbox data</h2>
        <p>Select saved source email records to download, including their attachment references. Exports do not include attachment files or AI verification reports.</p>
        <label class="data-file-label" for="sender-search">Search by sender</label>
        <input id="sender-search" v-model="senderSearch" class="sender-search" type="search" placeholder="Sender name or email address" />
        <div v-if="listError" class="data-error data-alert" role="alert">{{ listError }}</div>
        <p v-if="loadingEmails" role="status">Loading emails…</p>
        <template v-else-if="!listError">
          <div class="selection-tools">
            <label><input type="checkbox" :checked="allMatchingSelected" :indeterminate="matchingSelected > 0 && !allMatchingSelected" :disabled="!matchingEmails.length || !!exporting" @change="selectMatching" /> {{ senderSearch.trim() ? 'Select all matches' : 'Select all' }} ({{ matchingEmails.length }})</label>
            <button type="button" class="data-secondary" :disabled="!selectedIds.length || !!exporting" @click="selectedIds = []">Clear selection</button>
          </div>
          <div class="email-options" aria-label="Emails available for export">
            <label v-for="email in matchingEmails" :key="email.id" class="email-option">
              <input v-model="selectedIds" type="checkbox" :value="email.id" :disabled="!!exporting" />
              <span><strong>{{ email.senderName || email.sender }}</strong><span>{{ email.sender }}</span><span>{{ email.subject || '(No subject)' }}</span><small>{{ email.id }}</small></span>
            </label>
            <p v-if="!matchingEmails.length">{{ emails.length ? 'No senders match your search.' : 'No emails available. Import JSON to get started.' }}</p>
          </div>
          <p class="data-note" role="status">{{ selectedIds.length }} selected of {{ emails.length }} emails<span v-if="selectedIds.length > matchingSelected"> · {{ selectedIds.length - matchingSelected }} selected outside this search</span>. Exports include every selected email.</p>
        </template>
        <button type="button" class="data-secondary" :disabled="loadingEmails || busy || !!exporting" @click="loadEmails">Refresh email list</button>
        <div class="data-export-actions">
          <button type="button" :disabled="cannotExport" @click="exportData('json')">{{ exporting === 'json' ? 'Exporting…' : 'Download JSON' }}</button>
          <button type="button" class="data-secondary" :disabled="cannotExport" @click="exportData('csv')">{{ exporting === 'csv' ? 'Exporting…' : 'Download CSV' }}</button>
        </div>
        <p class="data-note"><strong>JSON:</strong> preserves source records and can be imported again.</p>
        <p class="data-note"><strong>CSV:</strong> one email per row, with attachment references in a JSON array. Formula-like text is escaped for spreadsheet safety.</p>
      </section>
    </div>

    <details class="data-format">
      <summary>View the email JSON format</summary>
      <p>Required fields: <code>email_id</code>, <code>from</code>, <code>subject</code>, <code>body</code>, and <code>attachments</code>. Use an empty array when there are no attachments.</p>
      <pre>{{ example }}</pre>
      <button type="button" class="data-secondary" @click="download(new Blob([example], { type: 'application/json' }), 'email-example.json')">Download example</button>
    </details>
  </section>
</template>

<style scoped>
.data-page { padding: 36px 0 56px; flex: 1; }
h1 { font-size: clamp(25px, 3vw, 36px); letter-spacing: -1px; margin: 8px 0 12px; }
.data-intro { max-width: 720px; color: #60756a; line-height: 1.7; margin-bottom: 28px; }
.data-grid { display: grid; grid-template-columns: 1.2fr 1fr; gap: 22px; }
.data-card { background: white; border: 1px solid #dce4de; border-radius: 16px; padding: 28px; min-width: 0; }
.data-step { font-size: 11px; font-weight: 700; letter-spacing: 1.4px; color: #6a7b70; }
h2 { margin: 14px 0; font-size: 22px; }
.data-card p, .data-format p { color: #60756a; font-size: 14px; line-height: 1.65; }
.data-file-label { display: block; font-size: 13px; font-weight: 600; margin: 24px 0 8px; }
.data-file { width: 100%; padding: 16px; border: 1px dashed #9daf9f; border-radius: 10px; background: #f5f8f4; color: #315648; }
.data-file::file-selector-button { border: 1px solid #c6d4c9; padding: 8px 12px; border-radius: 6px; background: white; color: #164f45; margin-right: 12px; cursor: pointer; }
.data-card .data-note { font-size: 12px; margin: 14px 0; }
.data-preview { background: #f2f6f0; border-radius: 10px; padding: 16px; font-size: 13px; margin: 18px 0; overflow-wrap: anywhere; }
.data-preview ul { padding-left: 18px; line-height: 1.8; }
.data-export-actions { display: flex; flex-wrap: wrap; gap: 10px; margin: 24px 0; }
.sender-search { width: 100%; box-sizing: border-box; border: 1px solid #b7c9bd; border-radius: 8px; padding: 12px; font: inherit; }
.selection-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; justify-content: space-between; margin: 16px 0; font-size: 13px; }
.selection-tools button { padding: 8px 12px; }
.email-options { max-height: 300px; overflow-y: auto; border: 1px solid #dce4de; border-radius: 8px; padding: 0 12px; }
.email-option { display: flex; align-items: flex-start; gap: 10px; padding: 12px 0; border-bottom: 1px solid #edf1ec; cursor: pointer; font-size: 13px; }
.email-option > span { min-width: 0; overflow-wrap: anywhere; }
.email-option span span, .email-option small { display: block; margin-top: 4px; color: #60756a; }
.email-option input { flex-shrink: 0; margin-top: 3px; }
.data-secondary { background: white; color: #164f45; border: 1px solid #b7c9bd; }
.data-secondary:hover { background: #edf3eb; }
.data-alert { padding: 18px 22px; border-radius: 12px; margin-bottom: 22px; overflow-wrap: anywhere; }
.data-alert p { margin: 8px 0; font-size: 14px; line-height: 1.6; }
.data-alert a { text-decoration: underline; font-weight: 600; }
.data-error { color: #943f31; background: #fff0e9; border: 1px solid #efc8b8; }
.data-success { background: #e9f3e7; border: 1px solid #bdd7b8; }
.data-format { margin-top: 24px; padding: 20px 24px; border: 1px solid #dce4de; border-radius: 12px; }
summary { cursor: pointer; font-weight: 600; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 760px) { .data-grid { grid-template-columns: 1fr; } .data-card { padding: 22px 18px; } }
</style>
