<script setup lang="ts">
defineProps<{
  title: string
  description: string
}>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-mark">∅</div>
    <h2>{{ title }}</h2>
    <p>{{ description }}</p>
    <!-- 默认插槽：给调用方放操作按钮（如「关注」流的「立即登录」）。
         不传就什么都不渲染，原有用法零影响 -->
    <slot />
  </div>
</template>
