<script setup>
</script>

<template>
  <div class="shell">
    <header>
      <router-link to="/" class="brand" aria-label="Shipping Verify home">
        <span class="brand-mark" aria-hidden="true">S</span>
        Shipping Verify
      </router-link>
      <nav class="nav-links">
        <router-link to="/" class="nav-link" exact-active-class="active">Status</router-link>
        <router-link to="/inbox" class="nav-link" active-class="active">Document Inbox</router-link>
      </nav>
      <span class="stage">Prototype setup</span>
    </header>

    <router-view />

    <footer>Shipping document verification <span>Hackathon prototype</span></footer>
  </div>
</template>
