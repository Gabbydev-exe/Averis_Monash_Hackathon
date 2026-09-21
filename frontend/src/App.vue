<script setup>
import ConnectionStatus from './components/ConnectionStatus.vue'
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
      </nav>
      <span class="stage">Document workspace</span>
    </header>

    <router-view />

    <footer>Shipping document verification <span>Hackathon prototype</span></footer>
  </div>
</template>

<style scoped>
.brand-stack { flex-shrink: 0; }
@media (max-width: 600px) {
  header { flex-wrap: wrap; gap: 12px; }
  .brand-stack { flex-basis: 100%; }
  .nav-links { width: 100%; }
  .nav-link { flex: 1; text-align: center; }
}
</style>
