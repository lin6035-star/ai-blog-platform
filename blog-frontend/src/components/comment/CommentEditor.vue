<script setup lang="ts">
import { computed, ref } from 'vue'
import { SendOutline } from '@vicons/ionicons5'

const props = defineProps<{
  loggedIn: boolean
  replyTarget?: string | null
  submitting?: boolean
  submitLabel?: string
  helperText?: string
  autofocus?: boolean
}>()

const emit = defineEmits<{
  submit: [content: string]
  login: []
  cancelReply: []
}>()

const content = ref('')

const placeholder = computed(() =>
  props.replyTarget ? `回复 @${props.replyTarget}` : '写下你的评论',
)

function handleSubmit() {
  if (props.submitting) {
    return
  }

  if (!props.loggedIn) {
    emit('login')
    return
  }

  const trimmed = content.value.trim()
  if (!trimmed) {
    return
  }

  emit('submit', trimmed)
}

function clear() {
  content.value = ''
}

defineExpose({ clear })
</script>

<template>
  <div class="comment-editor">
    <template v-if="loggedIn">
      <div v-if="replyTarget" class="reply-target">
        <span>正在回复 @{{ replyTarget }}</span>
        <button type="button" @click="emit('cancelReply')">取消</button>
      </div>
      <n-input
        v-model:value="content"
        type="textarea"
        :autofocus="autofocus"
        :placeholder="placeholder"
        :autosize="{ minRows: 3, maxRows: 6 }"
        maxlength="500"
        show-count
      />
      <div class="comment-editor-actions">
        <span>{{ helperText || '文明交流，理性表达' }}</span>
        <n-button
          type="info"
          :loading="submitting"
          :disabled="submitting || !content.trim()"
          @click="handleSubmit"
        >
          <template #icon>
            <n-icon><SendOutline /></n-icon>
          </template>
          {{ submitLabel || '发布' }}
        </n-button>
      </div>
    </template>
    <template v-else>
      <button class="comment-login-card" type="button" @click="emit('login')">
        登录后发表评论
      </button>
    </template>
  </div>
</template>
