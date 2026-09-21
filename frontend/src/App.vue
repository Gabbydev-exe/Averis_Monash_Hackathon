<script setup>
import { ref } from 'vue'
import ConnectionStatus from './components/ConnectionStatus.vue'
import WelcomeModal from './components/WelcomeModal.vue'

const showGuideModal = ref(false)

function openGuide() {
  showGuideModal.value = true
}
</script>

<template>
  <div class="shell">
    <header>
      <div class="brand-stack">
        <router-link to="/inbox" class="brand" aria-label="Shipping Verify inbox">
          <span class="brand-mark" aria-hidden="true">S</span>
          Shipping Verify
        </router-link>
        <ConnectionStatus />
      </div>
      <nav class="nav-links">
        <router-link to="/inbox" class="nav-link" active-class="active">Document Inbox</router-link>
        <router-link to="/data" class="nav-link" active-class="active">Import / Export</router-link>
        <button
          type="button"
          class="guide-btn"
          title="Open Quick Start Guide & Instructions"
          aria-label="Open Quick Start Guide"
          @click="openGuide"
        >
          <span class="guide-icon">💡</span> How It Works
        </button>
      </nav>
      <span class="stage">Document workspace</span>
    </header>

    <router-view />

    <footer>Shipping document verification <span>Hackathon prototype</span></footer>

    <!-- Automatic Welcome Popup Modal Overlay -->
    <WelcomeModal
      :force-open="showGuideModal"
      @close="showGuideModal = false"
    />
  </div>
</template>

<style scoped>
.brand-stack { flex-shrink: 0; }

.guide-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: transparent;
  border: none;
  font-size: 13px;
  font-weight: 600;
  color: #164f45;
  padding: 7px 12px;
  border-radius: 7px;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.guide-btn:hover {
  background: #dfe8e1;
  color: #123d35;
}

.guide-icon {
  font-size: 14px;
}

@media (max-width: 600px) {
  header { flex-wrap: wrap; gap: 12px; }
  .brand-stack { flex-basis: 100%; }
  .nav-links { width: 100%; }
  .nav-link { flex: 1; text-align: center; }
  .guide-btn { flex: 1; justify-content: center; text-align: center; }
}
</style>
