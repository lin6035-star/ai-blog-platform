<template>
  <div class="not-found-page">
    <div class="not-found-code">404</div>
    <p class="not-found-text">页面不存在，或者已经被删除了</p>
    <n-button type="primary" @click="goHome">回到首页</n-button>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  router.replace('/')
}
</script>

<style scoped>
.not-found-page {
  display: grid;
  place-items: center;
  place-content: center;
  gap: 16px;
  min-height: 60vh;
  padding: 40px 20px;
  color: #1f2937;
}

.not-found-code {
  font-size: 72px;
  font-weight: 700;
  line-height: 1;
  color: #2563eb;
}

.not-found-text {
  margin: 0;
  font-size: 15px;
  color: #6b7280;
}
</style>
