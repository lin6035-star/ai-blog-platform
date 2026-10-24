<script setup lang="ts">
import { CloseOutline, DownloadOutline } from '@vicons/ionicons5'

defineProps<{
  src: string
  show: boolean
}>()

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
}>()

function handleDownloadAvatar(src: string) {
  if (!src) return

  fetch(src)
    .then((res) => {
      if (!res.ok) throw new Error('fetch failed')
      return res.blob()
    })
    .then((blob) => {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `avatar-${Date.now()}.png`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
    })
    .catch(() => {
      window.open(src, '_blank', 'noopener,noreferrer')
    })
}
</script>

<template>
  <n-modal
    :show="show"
    :on-update:show="(val: boolean) => emit('update:show', val)"
    class="avatar-preview-modal"
  >
    <div class="avatar-preview-overlay">
      <button class="avatar-preview-close" type="button" @click="emit('update:show', false)" title="关闭">
        <n-icon size="24"><CloseOutline /></n-icon>
      </button>

      <img :src="src" class="avatar-preview-img" alt="头像大图" />

      <div class="avatar-preview-toolbar">
        <n-button
          size="large"
          @click="handleDownloadAvatar(src)"
        >
          <template #icon>
            <n-icon><DownloadOutline /></n-icon>
          </template>
          下载头像
        </n-button>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
.avatar-preview-overlay {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 22px;
  min-height: 100vh;
  padding: 24px;
}

.avatar-preview-close {
  position: fixed;
  top: 16px;
  right: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
  cursor: pointer;
  transition: background 0.2s;
}

.avatar-preview-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

.avatar-preview-img {
  width: min(76vw, 720px);
  max-width: 76vw;
  max-height: min(76vh, 720px);
  border-radius: 16px;
  object-fit: contain;
  box-shadow: 0 8px 48px rgba(0, 0, 0, 0.35);
  user-select: none;
}

.avatar-preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: min(76vw, 720px);
  max-width: 76vw;
  gap: 12px;
}
</style>
