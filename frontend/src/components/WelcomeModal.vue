<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'

const props = defineProps({
  forceOpen: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['close'])

const STORAGE_KEY = 'ship_ai_verifier_welcomed'
const isOpen = ref(false)
const dontShowAgain = ref(true)

function checkFirstVisit() {
  try {
    const hasVisited = localStorage.getItem(STORAGE_KEY)
    if (!hasVisited) {
      isOpen.value = true
    }
  } catch (e) {
    // Fallback if localStorage is inaccessible
    isOpen.value = true
  }
}

function closeModal() {
  isOpen.value = false
  if (dontShowAgain.value) {
    try {
      localStorage.setItem(STORAGE_KEY, 'true')
    } catch (e) {
      // Ignore storage errors
    }
  }
  emit('close')
}

function handleBackdropClick(event) {
  if (event.target === event.currentTarget) {
    closeModal()
  }
}

function handleKeyDown(event) {
  if (event.key === 'Escape' && isOpen.value) {
    closeModal()
  }
}

watch(
  () => props.forceOpen,
  (newVal) => {
    if (newVal) {
      isOpen.value = true
    }
  }
)

onMounted(() => {
  checkFirstVisit()
  window.addEventListener('keydown', handleKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeyDown)
})
</script>

<template>
  <Transition name="modal-fade">
    <div
      v-if="isOpen"
      class="modal-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby="welcome-modal-title"
      @click="handleBackdropClick"
    >
      <div class="modal-card">
        <!-- Close Button -->
        <button
          type="button"
          class="modal-close-btn"
          aria-label="Close welcome guide"
          @click="closeModal"
        >
          ✕
        </button>

        <!-- Header -->
        <div class="modal-header">
          <div class="header-icon-wrapper">
            <span class="header-icon">🚢</span>
          </div>
          <div>
            <div class="header-badge">Quick Start Guide</div>
            <h2 id="welcome-modal-title" class="modal-title">Welcome to Ship AI Verifier</h2>
            <p class="modal-subtitle">
              Import shipping emails, classify each request, and verify SI and draft B/L data with Gemini-assisted processing.
            </p>
          </div>
        </div>

        <!-- Scrollable Content Body -->
        <div class="modal-body">
          <!-- Section 1: How the Website Works -->
          <div class="info-section">
            <h3 class="section-title">
              <span class="title-icon">⚙️</span> How the System Works
            </h3>
            <div class="workflow-grid">
              <div class="workflow-card">
                <div class="card-step-num">1</div>
                <div class="card-content">
                  <h4>Import Emails & Documents</h4>
                  <p>
                    Import email records from JSON. For one new email, you can also attach zero to two PDF, Word, Excel, or TXT source documents. Existing email IDs are preserved and never overwritten.
                  </p>
                </div>
              </div>

              <div class="workflow-card">
                <div class="card-step-num">2</div>
                <div class="card-content">
                  <h4>Store & Link the Evidence</h4>
                  <p>
                    Email records and workflow results are saved in Cloud SQL. Uploaded documents are stored in private cloud object storage and linked to the correct email by its ID and filename.
                  </p>
                </div>
              </div>

              <div class="workflow-card">
                <div class="card-step-num">3</div>
                <div class="card-content">
                  <h4>Classify Every Email</h4>
                  <p>
                    Gemini reads the current subject and message and sorts it into <strong>SI/B/L comparison</strong>, <strong>new SI request</strong>, <strong>invoice query</strong>, <strong>general</strong>, or <strong>spam</strong>. Category tabs keep the inbox organized.
                  </p>
                </div>
              </div>

              <div class="workflow-card">
                <div class="card-step-num">4</div>
                <div class="card-content">
                  <h4>Process the Queue Automatically</h4>
                  <p>
                    Unprocessed emails enter a durable verification queue. Automatic processing handles eligible emails one at a time, avoids duplicate work across browser tabs, and saves each result. You can also run an email manually.
                  </p>
                </div>
              </div>

              <div class="workflow-card">
                <div class="card-step-num">5</div>
                <div class="card-content">
                  <h4>Extract & Compare Seven Fields</h4>
                  <p>
                    Gemini reads the email body and supported attachments, identifies SI and draft B/L sources, and extracts Shipper, Consignee, Notify Party, POL, POD, Container Count, and Gross Weight. Exact matches are verified automatically; any difference requires human review.
                  </p>
                </div>
              </div>

              <div class="workflow-card">
                <div class="card-step-num">6</div>
                <div class="card-content">
                  <h4>Review Evidence & Export Results</h4>
                  <p>
                    Inspect source filenames, field differences, AI match estimates, and original documents. Save an approval or issue flag with reviewer notes, then export processed results or source data as JSON or CSV.
                  </p>
                </div>
              </div>
            </div>
          </div>

          <!-- Section 2: What You Can Do (Suggested Things to Do) -->
          <div class="info-section">
            <h3 class="section-title">
              <span class="title-icon">💡</span> Suggested Things to Do
            </h3>
            <div class="action-list">
              <div class="action-item">
                <div class="action-icon">📥</div>
                <div class="action-text">
                  <strong>Import a New Email:</strong>
                  Open <em>Import / Export</em>, select a JSON file, optionally select up to two source documents, and choose <em>Import email and documents</em>.
                </div>
              </div>

              <div class="action-item">
                <div class="action-icon">📬</div>
                <div class="action-text">
                  <strong>Browse Classified Emails:</strong>
                  Use category tabs, status filters, or search to find SI/B/L comparisons, SI requests, invoice queries, general messages, spam, and emails needing review.
                </div>
              </div>

              <div class="action-item">
                <div class="action-icon">⚡</div>
                <div class="action-text">
                  <strong>Use Automatic Processing:</strong>
                  Leave <em>Automatically process unverified emails</em> enabled to work through the queue, or select an email and click <span class="badge-inline btn-pill">Run Verification</span> to process it immediately.
                </div>
              </div>

              <div class="action-item">
                <div class="action-icon">🔎</div>
                <div class="action-text">
                  <strong>Review the Evidence:</strong>
                  Open an email to compare SI and B/L fields, inspect the linked source documents, and approve or flag differences with a reviewer name and note.
                </div>
              </div>

              <div class="action-item">
                <div class="action-icon">📊</div>
                <div class="action-text">
                  <strong>Export Completed Work:</strong>
                  Select processed emails in <em>Import / Export</em> and download competition results or original source records in JSON or CSV format.
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="modal-footer">
          <label class="remember-checkbox-label">
            <input
              v-model="dontShowAgain"
              type="checkbox"
              class="remember-checkbox"
            />
            <span>Don't show this automatically again</span>
          </label>

          <button
            type="button"
            class="btn-get-started"
            @click="closeModal"
          >
            Get Started →
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
/* Modal Transition Animations */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.25s ease;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}

.modal-fade-enter-active .modal-card {
  transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.25s ease;
}

.modal-fade-enter-from .modal-card {
  opacity: 0;
  transform: scale(0.95) translateY(12px);
}

.modal-fade-leave-active .modal-card {
  transition: transform 0.2s ease, opacity 0.2s ease;
}

.modal-fade-leave-to .modal-card {
  opacity: 0;
  transform: scale(0.97) translateY(8px);
}

/* Backdrop Overlay */
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(15, 23, 42, 0.65);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  box-sizing: border-box;
}

/* Modal Card */
.modal-card {
  position: relative;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  width: 100%;
  max-width: 680px;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 40px -10px rgba(15, 23, 42, 0.25), 0 0 0 1px rgba(0, 0, 0, 0.05);
  color: #1e293b;
  overflow: hidden;
}

/* Close Button */
.modal-close-btn {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  color: #64748b;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
  z-index: 10;
}

.modal-close-btn:hover {
  background: #f1f5f9;
  color: #0f172a;
  border-color: #cbd5e1;
}

/* Header */
.modal-header {
  padding: 24px 24px 18px;
  background: linear-gradient(180deg, #f0fdf4 0%, #ffffff 100%);
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.header-icon-wrapper {
  width: 48px;
  height: 48px;
  background: #164f45;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex-shrink: 0;
  box-shadow: 0 4px 6px -1px rgba(22, 79, 69, 0.2);
}

.header-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: #15803d;
  background: #dcfce7;
  padding: 2px 8px;
  border-radius: 12px;
  margin-bottom: 4px;
}

.modal-title {
  font-size: 20px;
  font-weight: 800;
  color: #0f172a;
  margin: 0 0 4px;
  letter-spacing: -0.02em;
}

.modal-subtitle {
  font-size: 13px;
  color: #64748b;
  margin: 0;
  line-height: 1.45;
}

/* Body (Scrollable) */
.modal-body {
  padding: 20px 24px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.info-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-title {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  letter-spacing: -0.01em;
}

.title-icon {
  font-size: 16px;
}

/* Workflow Step Cards */
.workflow-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.workflow-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.card-step-num {
  background: #164f45;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}

.card-content h4 {
  font-size: 12px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 4px;
}

.card-content p {
  font-size: 11.5px;
  color: #475569;
  margin: 0;
  line-height: 1.4;
}

/* Actionable Checklist / Suggestions */
.action-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.action-item {
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 14px;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  font-size: 12.5px;
  color: #334155;
  line-height: 1.45;
}

.action-icon {
  font-size: 16px;
  flex-shrink: 0;
  margin-top: 1px;
}

.action-text strong {
  color: #0f172a;
}

.badge-inline {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  background: #2563eb;
  color: #ffffff;
  padding: 1px 6px;
  border-radius: 4px;
  margin: 0 2px;
}

/* Footer */
.modal-footer {
  padding: 16px 24px;
  border-top: 1px solid #e2e8f0;
  background: #fafafa;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.remember-checkbox-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: #64748b;
  cursor: pointer;
  user-select: none;
}

.remember-checkbox {
  cursor: pointer;
  accent-color: #164f45;
  width: 15px;
  height: 15px;
}

.btn-get-started {
  background: #164f45;
  color: #ffffff;
  border: none;
  padding: 9px 20px;
  border-radius: 8px;
  font-size: 13.5px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s ease;
  box-shadow: 0 2px 4px rgba(22, 79, 69, 0.2);
}

.btn-get-started:hover {
  background: #123d35;
  transform: translateY(-1px);
  box-shadow: 0 4px 8px rgba(22, 79, 69, 0.25);
}

.btn-get-started:active {
  transform: translateY(0);
}

/* Responsive adjustments */
@media (max-width: 640px) {
  .modal-card {
    max-height: 94vh;
  }

  .modal-header {
    padding: 18px 16px 14px;
    gap: 12px;
  }

  .header-icon-wrapper {
    width: 40px;
    height: 40px;
    font-size: 20px;
  }

  .modal-title {
    font-size: 17px;
  }

  .modal-body {
    padding: 16px;
    gap: 16px;
  }

  .workflow-grid {
    grid-template-columns: 1fr;
  }

  .modal-footer {
    padding: 14px 16px;
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
  }

  .btn-get-started {
    width: 100%;
    text-align: center;
    padding: 11px;
  }
}
</style>
