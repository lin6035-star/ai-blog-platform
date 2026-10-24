<script setup lang="ts">
import { FolderOpenOutline, Person, PricetagsOutline } from '@vicons/ionicons5'
import { storeToRefs } from 'pinia'
import type { Category } from '@/types/category'
import type { Tag } from '@/types/tag'
import { useAuthStore } from '@/stores/auth'

defineProps<{
  categories: Category[]
  tags: Tag[]
}>()

const { isLoggedIn, usersVO, displayName } = storeToRefs(useAuthStore())
</script>

<template>
  <aside class="right-sidebar">
    <section class="side-card user-card">
      <template v-if="isLoggedIn">
        <n-avatar :size="48" :src="usersVO?.avatarUrl">
          <template #fallback>
            <n-icon><Person /></n-icon>
          </template>
        </n-avatar>
        <div>
          <strong>{{ displayName }}</strong>
          <p>{{ usersVO?.bio || '继续写下你的技术轨迹。' }}</p>
          <div class="side-actions">
            <router-link to="/me">个人中心</router-link>
          </div>
        </div>
      </template>
      <template v-else>
        <strong>欢迎来到 FlowBlog</strong>
        <p>浏览公开文章，登录后可以创作和管理自己的内容。</p>
        <div class="side-actions">
          <router-link to="/login">登录</router-link>
          <router-link to="/register">注册</router-link>
        </div>
      </template>
    </section>

    <section class="side-card">
      <h2>
        <n-icon><FolderOpenOutline /></n-icon>
        分类
      </h2>
      <div v-if="categories.length" class="category-list">
        <button v-for="category in categories" :key="category.id" type="button">
          <span>{{ category.name }}</span>
          <small>{{ category.code }}</small>
        </button>
      </div>
      <p v-else class="muted">暂无分类</p>
    </section>

    <section class="side-card">
      <h2>
        <n-icon><PricetagsOutline /></n-icon>
        标签
      </h2>
      <div v-if="tags.length" class="tag-cloud">
        <n-tag v-for="tag in tags" :key="tag.id">
          {{ tag.name }}
        </n-tag>
      </div>
      <p v-else class="muted">暂无标签</p>
    </section>
  </aside>
</template>
