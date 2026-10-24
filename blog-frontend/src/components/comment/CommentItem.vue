<script setup lang="ts">
import { computed } from 'vue'
import {
  ChatbubbleEllipsesOutline,
  ChevronDownOutline,
  Heart,
  HeartOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import defaultAvatar from '@/assets/default-avatar.svg'
import type { Comment } from '@/types/comment'
import { formatCommentTime } from '@/utils/format'
import CommentEditor from './CommentEditor.vue'

const props = defineProps<{
  comment: Comment
  isReply?: boolean
  currentUserId?: string
  articleAuthorId?: string
  likingCommentIds?: string[]
  deletingCommentIds?: string[]
  loadingReplyIds?: string[]
  activeReplyId?: string | null
  replySubmittingId?: string | null
}>()

const emit = defineEmits<{
  toggleReply: [comment: Comment]
  submitReply: [comment: Comment, content: string]
  like: [comment: Comment]
  delete: [comment: Comment]
  loadReplies: [comment: Comment]
}>()

const displayName = computed(() =>
  props.comment.nickname?.trim() || `#${props.comment.userId}`,
)

const canDelete = computed(
  () =>
    props.currentUserId === props.comment.userId ||
    props.currentUserId === props.articleAuthorId,
)
const liking = computed(() => props.likingCommentIds?.includes(props.comment.id) ?? false)
const deleting = computed(() => props.deletingCommentIds?.includes(props.comment.id) ?? false)
const loadingReplies = computed(
  () => props.loadingReplyIds?.includes(props.comment.id) ?? false,
)
const isReplying = computed(() => props.activeReplyId === props.comment.id)
const replySubmitting = computed(() => props.replySubmittingId === props.comment.id)
const remainingReplyCount = computed(() =>
  Math.max(0, props.comment.replyCount - (props.comment.replies?.length ?? 0)),
)

function forwardSubmitReply(comment: Comment, content: string) {
  emit('submitReply', comment, content)
}
</script>

<template>
  <article class="comment-item" :class="{ 'comment-reply-item': isReply }">
    <img
      class="comment-avatar"
      :src="comment.avatarUrl || defaultAvatar"
      :alt="`${displayName} 的头像`"
    />
    <div class="comment-body">
      <div class="comment-heading">
        <strong>{{ displayName }}</strong>
        <span>{{ formatCommentTime(comment.createdAt) }}</span>
      </div>
      <p class="comment-content">
        <template v-if="comment.replyToNickname">
          <span class="reply-prefix">回复 @{{ comment.replyToNickname }}：</span>
        </template>
        {{ comment.content }}
      </p>
      <div class="comment-actions">
        <button
          class="comment-action"
          :class="{ active: comment.liked }"
          type="button"
          :disabled="liking"
          @click="emit('like', comment)"
        >
          <n-icon>
            <Heart v-if="comment.liked" />
            <HeartOutline v-else />
          </n-icon>
          {{ comment.likeCount || 0 }}
        </button>
        <button class="comment-action" type="button" @click="emit('toggleReply', comment)">
          <n-icon><ChatbubbleEllipsesOutline /></n-icon>
          {{ isReplying ? '取消回复' : '回复' }}
        </button>
        <button
          v-if="canDelete"
          class="comment-action"
          type="button"
          :disabled="deleting"
          @click="emit('delete', comment)"
        >
          <n-icon><TrashOutline /></n-icon>
          删除
        </button>
        <span v-if="comment.ipLocation" class="comment-location">
          {{ comment.ipLocation }}
        </span>
      </div>

      <div v-if="isReplying" class="comment-inline-editor">
        <CommentEditor
          :logged-in="Boolean(currentUserId)"
          :reply-target="displayName"
          :submitting="replySubmitting"
          submit-label="回复"
          autofocus
          @submit="emit('submitReply', comment, $event)"
          @cancel-reply="emit('toggleReply', comment)"
        />
      </div>

      <div
        v-if="!isReply && (comment.replies?.length || comment.replyCount > 0)"
        class="comment-replies"
      >
        <CommentItem
          v-for="reply in comment.replies"
          :key="reply.id"
          :comment="reply"
          is-reply
          :current-user-id="currentUserId"
          :article-author-id="articleAuthorId"
          :liking-comment-ids="likingCommentIds"
          :deleting-comment-ids="deletingCommentIds"
          :loading-reply-ids="loadingReplyIds"
          :active-reply-id="activeReplyId"
          :reply-submitting-id="replySubmittingId"
          @toggle-reply="emit('toggleReply', $event)"
          @submit-reply="forwardSubmitReply"
          @like="emit('like', $event)"
          @delete="emit('delete', $event)"
          @load-replies="emit('loadReplies', $event)"
        />
        <button
          v-if="remainingReplyCount > 0"
          class="comment-replies-more"
          type="button"
          :disabled="loadingReplies"
          @click="emit('loadReplies', comment)"
        >
          <n-icon><ChevronDownOutline /></n-icon>
          {{ loadingReplies ? '加载中' : `展开剩余 ${remainingReplyCount} 条回复` }}
        </button>
      </div>
    </div>
  </article>
</template>
