<script setup lang="ts">
import { computed } from 'vue'
import {
  ChatbubbleOutline,
  Eye,
  Heart,
  HeartOutline,
  Star,
  StarOutline,
} from '@vicons/ionicons5'
import type { Article } from '@/types/article'
import { sanitizeHtml } from '@/utils/sanitizeHtml'
import { highlightByKeyword } from '@/utils/highlight'
import { formatArticleDate } from '@/utils/format'

const props = defineProps<{
  article: Article
  /**
   * 当前搜索关键词。**只在搜索结果列表传**（首页带 keyword 时），
   * 用途是 ES 降级到 LIKE 时的兜底高亮——那时后端一个高亮都没有。
   */
  keyword?: string
}>()

const emit = defineEmits<{
  open: [article: Article]
  like: [article: Article]
  favorite: [article: Article]
  'author-click': [authorId: string]
}>()

const authorDisplayName = computed(() =>
  props.article.authorName?.trim() || `#${props.article.authorId}`,
)

/** 没头像时的兜底：取昵称首字。比一个通用人形图标更能区分作者 */
const authorInitial = computed(() => authorDisplayName.value.trim().charAt(0) || '?')

/**
 * 标题的高亮 HTML。
 *
 * 优先用后端 ES 高亮：它和检索用的是同一套 IK 分词，所以"亮的词"与"命中的词"一致，
 * 而且中文多词查询（搜「缓存穿透」命中「缓存与穿透」）也能正确标出来。
 *
 * 后端没给（ES 降级到 LIKE）时才退回本地纯文本高亮。
 *
 * **返回 null 表示"不必用 v-html"**——非搜索场景保持原来的纯文本渲染，
 * 不让 v-html 出现在列表热路径上。
 */
const titleHtml = computed(() => {
  if (props.article.highlightTitle) {
    return sanitizeHtml(props.article.highlightTitle)
  }
  if (props.keyword && props.article.title) {
    return highlightByKeyword(props.article.title, props.keyword)
  }
  return null
})

/** 摘要行的高亮 HTML。片段优先（它可能来自正文——那正是"正文哪里命中了"），否则退回本地高亮摘要 */
const summaryHtml = computed(() => {
  if (props.article.highlight) {
    return sanitizeHtml(props.article.highlight)
  }
  if (props.keyword && props.article.summary) {
    return highlightByKeyword(props.article.summary, props.keyword)
  }
  return null
})
</script>

<template>
  <article class="article-card">
    <div
      class="article-link"
      role="link"
      tabindex="0"
      @click="emit('open', article)"
      @keydown.enter="emit('open', article)"
    >
      <div class="article-main">
        <div class="article-meta">
          <button
            class="article-author-link"
            type="button"
            @click.prevent.stop="emit('author-click', article.authorId)"
          >
            <!-- 头像放在昵称**前面**：扫列表时先认脸、再认名字 -->
            <n-avatar :size="22" :src="article.avatarUrl" class="article-author-avatar">
              <template #fallback>
                <span class="article-author-initial">{{ authorInitial }}</span>
              </template>
            </n-avatar>
            作者：{{ authorDisplayName }}
          </button>
          <span>{{ formatArticleDate(article.publishedAt) }}</span>
          <span v-if="article.categoryName" class="article-category-tag">
            #{{ article.categoryName }}
          </span>
        </div>
        <!-- v-html 只在搜索场景出现：titleHtml / summaryHtml 为 null 时走原来的纯文本渲染。
             两个 HTML 来源都已过 DOMPurify（后端片段过 sanitizeHtml，
             本地兜底高亮在 highlightByKeyword 内部清洗） -->
        <h2 v-if="titleHtml" v-html="titleHtml"></h2>
        <h2 v-else>{{ article.title }}</h2>
        <p v-if="summaryHtml" v-html="summaryHtml"></p>
        <p v-else>{{ article.summary || '这篇文章暂时没有摘要，点击查看完整内容。' }}</p>
        <div class="article-stats">
          <span>
            <n-icon><Eye /></n-icon>
            {{ article.viewCount || 0 }}
          </span>
          <span>
            <n-icon><ChatbubbleOutline /></n-icon>
            {{ article.commentCount || 0 }}
          </span>
          <button
            class="article-stat-btn"
            :class="{ active: article.liked === 1 }"
            type="button"
            @click.prevent.stop="emit('like', article)"
          >
            <n-icon>
              <Heart v-if="article.liked === 1" />
              <HeartOutline v-else />
            </n-icon>
            {{ article.likeCount || 0 }}
          </button>
          <button
            class="article-stat-btn"
            :class="{ active: article.favorited === 1 }"
            type="button"
            @click.prevent.stop="emit('favorite', article)"
          >
            <n-icon>
              <Star v-if="article.favorited === 1" />
              <StarOutline v-else />
            </n-icon>
            {{ article.favoriteCount || 0 }}
          </button>
        </div>
      </div>
      <img
        v-if="article.coverUrl"
        class="article-cover"
        :src="article.coverUrl"
        alt=""
      />
    </div>
  </article>
</template>

<style scoped>
/* 头像与昵称对齐。
   用 inline-flex 是为了**不依赖全局 .article-author-link 的 display** ——
   那个类原本只装一行文字，display 很可能是 inline，而 n-avatar 是块级，
   塞进去会掉到下一行（不改全局样式，免得影响别处复用它的地方）。 */
.article-author-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 窄屏下别把头像压扁成椭圆 */
.article-author-avatar {
  flex-shrink: 0;
}

/* 兜底首字：22px 的圆里放 12px 刚好 */
.article-author-initial {
  font-size: 12px;
  line-height: 1;
}
</style>
