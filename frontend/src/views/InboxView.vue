<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const searchQuery = ref('')
const selectedFilter = ref('all')
const selectedEmailId = ref(1)
const isVerifying = ref(false)

const emails = ref([
  {
    id: 1,
    sender: 'ops@maersk-oceanlines.com',
    senderName: 'Maersk Customer Support',
    subject: 'Draft B/L & Shipping Instruction - Booking #MAE-2026-9941',
    date: 'Today, 11:42 AM',
    unread: true,
    status: 'discrepancy', // 'pending' | 'verified' | 'discrepancy'
    bookingNo: 'MAE-2026-9941',
    vessel: 'MAERSK MC-KINNEY / V.2401W',
    pol: 'Port of Tanjung Pelepas (MYTPP)',
    pod: 'Port of Rotterdam (NLRTM)',
    attachments: [
      { name: 'SI_MAE-2026-9941.pdf', size: '245 KB', type: 'SI' },
      { name: 'Draft_BL_MAE-2026-9941.pdf', size: '312 KB', type: 'BL' }
    ],
    fields: [
      { label: 'Shipper', si: 'Averis Global Exports Sdn Bhd, Kuala Lumpur', bl: 'Averis Global Exports Sdn Bhd, Kuala Lumpur', status: 'match' },
      { label: 'Consignee', si: 'EuroTrade Logistics B.V., Rotterdam', bl: 'EuroTrade Logistics B.V., Rotterdam', status: 'match' },
      { label: 'Notify Party', si: 'EuroTrade Logistics B.V., Rotterdam', bl: 'EuroTrade Logistics B.V., Rotterdam', status: 'match' },
      { label: 'Port of Loading', si: 'Port of Tanjung Pelepas, MY', bl: 'Port of Tanjung Pelepas, MY', status: 'match' },
      { label: 'Port of Discharge', si: 'Port of Rotterdam, NL', bl: 'Port of Rotterdam, NL', status: 'match' },
      { label: 'Container Count', si: '1 x 40HC', bl: '1 x 40HC', status: 'match' },
      { label: 'Gross Weight (kg)', si: '24,500.00 KG', bl: '24,650.00 KG', status: 'mismatch', note: 'Weight variance +150 KG (+0.61%)' }
    ],
    bodyText: 'Dear Shipping Team,\n\nPlease find attached the Shipping Instruction (SI) and carrier draft Bill of Lading (B/L) for Booking #MAE-2026-9941. Kindly verify and confirm if the details match before final release.\n\nBest regards,\nMaersk Documentation Desk'
  },
  {
    id: 2,
    sender: 'doc.sg@msc.com',
    senderName: 'MSC Mediterranean Shipping Co.',
    subject: 'Verification Required: SI vs Draft BL - Booking #MSC-88310',
    date: 'Today, 09:15 AM',
    unread: false,
    status: 'verified',
    bookingNo: 'MSC-88310',
    vessel: 'MSC OSCAR / V.302E',
    pol: 'Port Klang (MYPKG)',
    pod: 'Port of Hamburg (DEHAM)',
    attachments: [
      { name: 'SI_MSC-88310_Final.pdf', size: '180 KB', type: 'SI' },
      { name: 'Draft_BL_MEDU12903.pdf', size: '290 KB', type: 'BL' }
    ],
    fields: [
      { label: 'Shipper', si: 'Pacific Oleo Chemicals Sdn Bhd', bl: 'Pacific Oleo Chemicals Sdn Bhd', status: 'match' },
      { label: 'Consignee', si: 'Hanseatic ChemDist GmbH, Hamburg', bl: 'Hanseatic ChemDist GmbH, Hamburg', status: 'match' },
      { label: 'Notify Party', si: 'Hanseatic ChemDist GmbH, Hamburg', bl: 'Hanseatic ChemDist GmbH, Hamburg', status: 'match' },
      { label: 'Port of Loading', si: 'Port Klang, Malaysia', bl: 'Port Klang, Malaysia', status: 'match' },
      { label: 'Port of Discharge', si: 'Hamburg, Germany', bl: 'Hamburg, Germany', status: 'match' },
      { label: 'Container Count', si: '1 x 20GP', bl: '1 x 20GP', status: 'match' },
      { label: 'Gross Weight (kg)', si: '18,200.00 KG', bl: '18,200.00 KG', status: 'match' }
    ],
    bodyText: 'Good day,\n\nPlease review the attached Draft B/L for booking MSC-88310 against your shipping instructions. Everything appears aligned on our side.\n\nThank you,\nMSC Documentation Team'
  },
  {
    id: 3,
    sender: 'forwarding@cma-cgm.com',
    senderName: 'CMA CGM Agency Malaysia',
    subject: 'New Submission: SI & Draft BL for Vessel CMA CGM BOUGAINVILLE',
    date: 'Yesterday, 04:30 PM',
    unread: false,
    status: 'pending',
    bookingNo: 'CMA-MY-44019',
    vessel: 'CMA CGM BOUGAINVILLE / V.0FM8EE',
    pol: 'Port of Tanjung Pelepas (MYTPP)',
    pod: 'Port of Antwerp (BEANR)',
    attachments: [
      { name: 'SI_CMA_44019.pdf', size: '210 KB', type: 'SI' },
      { name: 'BL_Draft_ANR_44019.pdf', size: '340 KB', type: 'BL' }
    ],
    fields: [
      { label: 'Shipper', si: 'Averis Biofuels Division', bl: 'Averis Biofuels Division', status: 'pending' },
      { label: 'Consignee', si: 'Belgian Bio Energy NV', bl: 'Belgian Bio Energy NV', status: 'pending' },
      { label: 'Notify Party', si: 'Belgian Bio Energy NV', bl: 'Belgian Bio Energy NV', status: 'pending' },
      { label: 'Port of Loading', si: 'Tanjung Pelepas, MY', bl: 'Tanjung Pelepas, MY', status: 'pending' },
      { label: 'Port of Discharge', si: 'Antwerp, Belgium', bl: 'Antwerp, Belgium', status: 'pending' },
      { label: 'Container Count', si: '1 x 40HC', bl: '1 x 40HC', status: 'pending' },
      { label: 'Gross Weight (kg)', si: '26,000.00 KG', bl: '26,000.00 KG', status: 'pending' }
    ],
    bodyText: 'Dear customer,\n\nWe have received your Shipping Instruction and prepared the draft Bill of Lading. Please run the automated verification and notify us if amendments are necessary.\n\nRegards,\nCMA CGM Customer Care'
  },
  {
    id: 4,
    sender: 'bl-desk@evergreen-marine.com',
    senderName: 'Evergreen Marine Desk',
    subject: 'Draft B/L Review - Booking #EMC-2026-1188 - Discrepancy Alert',
    date: 'Sep 18, 02:10 PM',
    unread: false,
    status: 'discrepancy',
    bookingNo: 'EMC-2026-1188',
    vessel: 'EVER GIVEN / V.0612-088E',
    pol: 'Port Klang (MYPKG)',
    pod: 'Port of Los Angeles (USLAX)',
    attachments: [
      { name: 'SI_EMC1188.pdf', size: '198 KB', type: 'SI' },
      { name: 'Draft_BL_EMC1188.pdf', size: '275 KB', type: 'BL' }
    ],
    fields: [
      { label: 'Shipper', si: 'Averis Specialty Agrochemicals', bl: 'Averis Specialty Agrochemicals', status: 'match' },
      { label: 'Consignee', si: 'California AgriSupply Inc, Long Beach', bl: 'California AgriSupply LLC, Los Angeles', status: 'mismatch', note: 'Entity & City mismatch: Inc (Long Beach) vs LLC (Los Angeles)' },
      { label: 'Notify Party', si: 'California AgriSupply Inc, Long Beach', bl: 'California AgriSupply LLC, Los Angeles', status: 'mismatch', note: 'Entity & City mismatch: Inc (Long Beach) vs LLC (Los Angeles)' },
      { label: 'Port of Loading', si: 'Port Klang, Malaysia', bl: 'Port Klang, Malaysia', status: 'match' },
      { label: 'Port of Discharge', si: 'Port of Los Angeles, USA', bl: 'Port of Los Angeles, USA', status: 'match' },
      { label: 'Container Count', si: '1 x 40HC', bl: '1 x 40HC', status: 'match' },
      { label: 'Gross Weight (kg)', si: '21,400.00 KG', bl: '21,400.00 KG', status: 'match' }
    ],
    bodyText: 'Attention Documentation Desk,\n\nPlease review the draft B/L for shipment EMC-2026-1188. Automated cross-check flagged consignee naming and notify party discrepancies.\n\nRegards,\nEvergreen Marine Corp.'
  }
])

const stats = computed(() => {
  const all = emails.value.length
  const verified = emails.value.filter(e => e.status === 'verified').length
  const discrepancy = emails.value.filter(e => e.status === 'discrepancy').length
  const pending = emails.value.filter(e => e.status === 'pending').length
  return { all, verified, discrepancy, pending }
})

const filteredEmails = computed(() => {
  return emails.value.filter(email => {
    const matchesFilter =
      selectedFilter.value === 'all' || email.status === selectedFilter.value
    const query = searchQuery.value.trim().toLowerCase()
    if (!query) return matchesFilter
    const matchesSearch =
      email.subject.toLowerCase().includes(query) ||
      email.sender.toLowerCase().includes(query) ||
      email.senderName.toLowerCase().includes(query) ||
      email.bookingNo.toLowerCase().includes(query) ||
      email.vessel.toLowerCase().includes(query)
    return matchesFilter && matchesSearch
  })
})

const currentEmail = computed(() => {
  return emails.value.find(e => e.id === selectedEmailId.value) || emails.value[0]
})

function selectEmail(email) {
  selectedEmailId.value = email.id
  email.unread = false
}

function runVerification() {
  if (!currentEmail.value) return
  isVerifying.value = true
  setTimeout(() => {
    isVerifying.value = false
    // Simulate verification complete
    let hasMismatch = false
    currentEmail.value.fields.forEach(f => {
      if (f.si !== f.bl) {
        f.status = 'mismatch'
        hasMismatch = true
      } else {
        f.status = 'match'
      }
    })
    currentEmail.value.status = hasMismatch ? 'discrepancy' : 'verified'
  }, 900)
}

function approveMatch() {
  if (!currentEmail.value) return
  currentEmail.value.status = 'verified'
  currentEmail.value.fields.forEach(f => {
    f.status = 'match'
  })
}

function flagDiscrepancy() {
  if (!currentEmail.value) return
  currentEmail.value.status = 'discrepancy'
}
</script>

<template>
  <div class="inbox-page">
    <!-- Top Navigation Bar -->
    <div class="inbox-header">
      <div class="inbox-title-group">
        <button type="button" class="back-link" @click="router.push('/')" aria-label="Back to System Status">
          ← Back to Status
        </button>
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
          <span class="stat-lbl">Pending Review</span>
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

    <!-- Main Workspace: Inbox List + Detail Verification Panel -->
    <div class="inbox-layout">
      <!-- Left: Email List -->
      <section class="inbox-sidebar" aria-label="Email list">
        <div class="sidebar-controls">
          <input
            v-model="searchQuery"
            type="search"
            class="search-input"
            placeholder="Search booking #, vessel, shipper..."
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
              Pending ({{ stats.pending }})
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
          </div>
        </div>

        <ul class="email-list" role="list">
          <li
            v-for="email in filteredEmails"
            :key="email.id"
            :class="[
              'email-item',
              { active: currentEmail && currentEmail.id === email.id, unread: email.unread }
            ]"
            @click="selectEmail(email)"
          >
            <div class="email-item-header">
              <span class="sender-name">{{ email.senderName }}</span>
              <span class="email-date">{{ email.date }}</span>
            </div>
            <div class="email-subject">{{ email.subject }}</div>
            <div class="email-meta">
              <span class="booking-tag">{{ email.bookingNo }}</span>
              <span :class="['status-pill', email.status]">
                {{ email.status === 'verified' ? '✓ Verified' : email.status === 'discrepancy' ? '⚠ Discrepancy' : '⏳ Pending' }}
              </span>
            </div>
          </li>
          <li v-if="filteredEmails.length === 0" class="no-emails">
            No emails found matching the selected filter.
          </li>
        </ul>
      </section>

      <!-- Right: Document Verification Detail -->
      <section v-if="currentEmail" class="inbox-detail" aria-label="Email and Verification Detail">
        <!-- Email Header Banner -->
        <div class="detail-header">
          <div class="detail-title-section">
            <div class="detail-tags">
              <span class="booking-tag-lg">Booking: {{ currentEmail.bookingNo }}</span>
              <span :class="['status-pill-lg', currentEmail.status]">
                {{ currentEmail.status === 'verified' ? 'Verified & Matched' : currentEmail.status === 'discrepancy' ? 'Discrepancy Detected' : 'Pending Verification' }}
              </span>
            </div>
            <h2>{{ currentEmail.subject }}</h2>
            <div class="sender-info">
              <strong>From:</strong> {{ currentEmail.senderName }} &lt;{{ currentEmail.sender }}&gt; • <span>{{ currentEmail.date }}</span>
            </div>
          </div>

          <div class="detail-actions">
            <button
              type="button"
              class="btn-verify"
              :disabled="isVerifying"
              @click="runVerification"
            >
              {{ isVerifying ? 'Verifying Documents…' : '⚡ Run Verification' }}
            </button>
            <button
              type="button"
              class="btn-approve"
              @click="approveMatch"
            >
              ✓ Approve
            </button>
            <button
              type="button"
              class="btn-flag"
              @click="flagDiscrepancy"
            >
              ⚠ Flag Issue
            </button>
          </div>
        </div>

        <!-- Document Attachments Bar -->
        <div class="attachments-card">
          <div class="attachments-title">
            <span>📎 Attached Shipping Documents (2)</span>
            <span class="ocr-status">Ready for extraction</span>
          </div>
          <div class="attachments-list">
            <div
              v-for="(att, i) in currentEmail.attachments"
              :key="i"
              class="attachment-chip"
            >
              <span class="att-type" :class="att.type">{{ att.type }}</span>
              <span class="att-name">{{ att.name }}</span>
              <span class="att-size">{{ att.size }}</span>
            </div>
          </div>
        </div>

        <!-- Verification Table: SI vs BL Comparison -->
        <div class="verification-section">
          <div class="verification-header">
            <div>
              <h3>Shipping Instruction (SI) vs. Draft Bill of Lading (B/L)</h3>
              <p class="section-desc">Automated field-by-field cross comparison highlighting any discrepancies</p>
            </div>
            <div class="vessel-badge">
              <span>🚢 Vessel: <strong>{{ currentEmail.vessel }}</strong></span>
            </div>
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
                  v-for="(field, index) in currentEmail.fields"
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
                    <div v-if="field.note" class="mismatch-note">
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
          <p class="email-text">{{ currentEmail.bodyText }}</p>
        </div>
      </section>
    </div>
  </div>
</template>
