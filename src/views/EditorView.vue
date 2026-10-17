<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useDialog, useMessage } from 'naive-ui'
import { ArrowBack } from '@vicons/ionicons5'
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import MainLayout from '@/layouts/MainLayout.vue'
import { categoryApi } from '@/api/category'
import { myArticleApi, type ArticleForm } from '@/api/myArticle'
import { ARTICLE_STATUS, type ArticleStatus } from '@/constants/articleStatus'
import { extractArticleSummary } from '@/utils/articleSummary'
import { sanitizeHtml } from '@/utils/sanitizeHtml'
import type { Category } from '@/types/category'
import { registerAiEditorHandler, unregisterAiEditorHandler } from '@/utils/aiEditorBus'
import type { EditorAction } from '@/api/ai'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const dialog = useDialog()

/* ---- 编辑模式 ---- */
const editId = computed(() => {
  const id = route.params.id
  return id ? String(id) : null
})
const isEditingPublished = computed(
  () => editId.value !== null && form.status === ARTICLE_STATUS.PUBLISHED,
)

/* ---- 表单 ---- */
const form = reactive<ArticleForm>({
  categoryId: null,
  title: '',
  summary: '',
  content: '',
  coverUrl: '',
  status: ARTICLE_STATUS.DRAFT,
})
const initialContent = ref('')
const initialTitle = ref('')
const initialCoverUrl = ref('')
const initialCategoryId = ref<string | null>(null)
const saving = ref(false)

/* ---- AI 填充竞态保护：编辑模式下原文未加载完时暂存，防止优化内容被原文覆盖 ---- */
const articleLoaded = ref(false)
let pendingFillAction: EditorAction | null = null

/* ---- 分类 ---- */
const DEFAULT_CATEGORY_CODE = 'notes'
const DEFAULT_CATEGORY_NAME = '随笔'
const categories = ref<Category[]>([])

function resolveSubmitCategoryId() {
  if (form.categoryId !== null) {
    return form.categoryId
  }

  return categories.value.find((category) => category.code === DEFAULT_CATEGORY_CODE)?.id
    ?? categories.value.find((category) => category.name === DEFAULT_CATEGORY_NAME)?.id
    ?? null
}

/* ---- 封面上传 ---- */
const coverUploading = ref(false)
const coverInput = ref<HTMLInputElement>()

async function handleCoverUpload(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  coverUploading.value = true
  try {
    const res = await myArticleApi.uploadImage(file)
    form.coverUrl = res.data
    message.success('封面上传成功')
  } catch {
    message.error('封面上传失败')
  } finally {
    coverUploading.value = false
    // 清空 input，允许重复选同一文件
    if (coverInput.value) coverInput.value.value = ''
  }
}

/* ---- 脏检测 ---- */
const skipGuard = ref(false)

const isDirty = computed(() => {
  if (editId.value) {
    return (
      form.title !== initialTitle.value ||
      form.content !== initialContent.value ||
      form.coverUrl !== initialCoverUrl.value ||
      form.categoryId !== initialCategoryId.value
    )
  }
  return (
    form.title.trim() !== '' ||
    form.content !== '' ||
    form.coverUrl !== '' ||
    form.categoryId !== null
  )
})

/* ---- 图片上传 ---- */
async function handleUploadImg(files: File[], callback: (urls: string[]) => void) {
  const urls: string[] = []
  for (const file of files) {
    try {
      const res = await myArticleApi.uploadImage(file)
      urls.push(res.data)
    } catch {
      message.error(`图片 ${file.name} 上传失败`)
    }
  }
  callback(urls)
}

async function saveArticle(
  status: ArticleStatus,
  successText: string,
  options: { navigateBack?: boolean; failureText?: string } = {},
) {
  if (!form.title.trim()) {
    message.warning('请输入文章标题')
    return false
  }

  saving.value = true
  form.status = status
  form.summary = extractArticleSummary(form.content)
  const submitForm: ArticleForm = {
    ...form,
    categoryId: resolveSubmitCategoryId(),
  }

  try {
    if (editId.value) {
      await myArticleApi.update(editId.value, submitForm)
    } else {
      const res = await myArticleApi.create(submitForm)
      const id = res.data
      if (id) {
        skipGuard.value = true
        router.replace({ name: 'editor-edit', params: { id } })
      }
    }
    message.success(successText)
    initialContent.value = form.content
    initialTitle.value = form.title
    initialCoverUrl.value = form.coverUrl
    initialCategoryId.value = form.categoryId
    if (options.navigateBack) {
      skipGuard.value = true
      router.back()
    }
    return true
  } catch (e) {
    const msg = e instanceof Error ? e.message : options.failureText ?? '保存失败'
    message.error(msg)
    return false
  } finally {
    saving.value = false
  }
}

/* ---- 保存草稿 ---- */
async function handleAiEditorAction(action: EditorAction) {
  if (action.type === 'fillArticle') {
    // 编辑模式：原文还没加载完时先暂存，等 getDetail 完成后再填充，防止原文覆盖优化内容
    if (editId.value && !articleLoaded.value) {
      pendingFillAction = action
      return
    }

    if (action.title) form.title = action.title
    if (action.content) form.content = action.content
    if (action.summary) form.summary = action.summary

    if (action.categoryName) {
      const matched = categories.value.find((c) => c.name === action.categoryName)
      if (matched) {
        form.categoryId = matched.id
      }
    }

    message.success('AI 已填充文章草稿')
    return
  }

  if (action.type === 'saveDraft') {
    await (isEditingPublished.value ? handleSavePublishedChange() : handleSaveDraft())
    return
  }

  if (action.type === 'publish') {
    dialog.warning({
      title: '确认发布',
      content: 'AI 请求发布当前文章，确认发布吗？',
      positiveText: '发布',
      negativeText: '取消',
      onPositiveClick: () => handlePublish(),
    })
  }
}

async function handleSaveDraft() {
  if (isEditingPublished.value) {
    message.warning('已发布文章不能保存为草稿')
    return false
  }

  return await saveArticle(ARTICLE_STATUS.DRAFT, '已保存至草稿箱')
}

async function handleSavePublishedChange() {
  return await saveArticle(ARTICLE_STATUS.PUBLISHED, '文章已更新')
}

/* ---- 发布 ---- */
async function handlePublish() {
  await saveArticle(ARTICLE_STATUS.PUBLISHED, isEditingPublished.value ? '文章已更新' : '发布成功', {
    navigateBack: true,
    failureText: isEditingPublished.value ? '保存失败' : '发布失败',
  })
}

/* ---- 返回 ---- */
function handleBack() {
  router.back()
}

/* ---- 键盘快捷键 Ctrl+S 保存 ---- */
function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    isEditingPublished.value ? handleSavePublishedChange() : handleSaveDraft()
  }
}

/* ---- 生命周期 ---- */
onMounted(async () => {
  document.addEventListener('keydown', onKeydown)
  registerAiEditorHandler(handleAiEditorAction)

  // 加载分类
  try {
    const res = await categoryApi.getList()
    categories.value = res.data ?? []
  } catch { /* 忽略 */ }

  // 编辑模式：加载文章
  if (editId.value) {
    try {
      const res = await myArticleApi.getDetail(editId.value)
      const article = res.data
      form.categoryId = article.categoryId
      form.title = article.title
      form.summary = article.summary
      form.content = article.content
      form.coverUrl = article.coverUrl
      form.status = article.status
      initialContent.value = article.content
      initialTitle.value = article.title
      initialCoverUrl.value = article.coverUrl
      initialCategoryId.value = article.categoryId

      articleLoaded.value = true
      // 处理在原文加载期间到达的 AI 填充
      if (pendingFillAction) {
        await handleAiEditorAction(pendingFillAction)
        pendingFillAction = null
      }
    } catch {
      message.error('文章加载失败')
      router.back()
    }
  } else {
    articleLoaded.value = true
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  unregisterAiEditorHandler(handleAiEditorAction)
})

/* ---- 路由离开守卫 ---- */
onBeforeRouteLeave((_to, _from, next) => {
  if (skipGuard.value) {
    skipGuard.value = false
    next()
    return
  }
  if (isDirty.value) {
    dialog.warning({
      title: '离开确认',
      content: isEditingPublished.value
        ? '你还有未保存的修改，是否保存并更新后再离开？'
        : '你还有未保存的内容，是否保存草稿后再离开？',
      positiveText: isEditingPublished.value ? '保存并更新文章' : '保存草稿',
      negativeText: isEditingPublished.value ? '取消' : '不保存',
      onPositiveClick: async () => {
        const saved = await (isEditingPublished.value ? handleSavePublishedChange() : handleSaveDraft())
        if (saved) {
          next()
        } else {
          next(false)
        }
      },
      onNegativeClick: () => {
        next()
      },
      onClose: () => {
        next(false)
      },
    })
  } else {
    next()
  }
})
</script>

<template>
  <MainLayout>
    <div class="editor-page">
    <!-- 顶部工具栏 -->
    <header class="editor-toolbar">
      <button class="editor-back" type="button" @click="handleBack">
        <n-icon size="20"><ArrowBack /></n-icon>
      </button>

      <input
        class="editor-title-input"
        v-model="form.title"
        type="text"
        placeholder="输入文章标题…"
        maxlength="120"
      />

      <div class="editor-actions">
        <button
          v-if="!isEditingPublished"
          class="editor-btn editor-btn-draft"
          type="button"
          :disabled="saving"
          @click="handleSaveDraft"
        >
          {{ saving ? '…' : '保存草稿' }}
        </button>
        <button
          class="editor-btn editor-btn-publish"
          type="button"
          :disabled="saving"
          @click="handlePublish"
        >
          {{ saving ? '…' : isEditingPublished ? '更新文章' : '发布' }}
        </button>
      </div>
    </header>

    <!-- 配置栏 -->
    <div class="editor-config">
      <div class="config-field">
        <label>分类</label>
        <n-select
          v-model:value="form.categoryId"
          :options="categories.map(c => ({ label: c.name, value: c.id }))"
          placeholder="选择分类"
          clearable
          style="width: 160px"
        />
      </div>

      <div class="config-field">
        <label>封面图</label>
        <input
          ref="coverInput"
          type="file"
          accept="image/*"
          style="display: none"
          @change="handleCoverUpload"
        />
        <button
          class="cover-upload-btn"
          type="button"
          :disabled="coverUploading"
          @click="coverInput?.click()"
        >
          {{ coverUploading ? '上传中…' : form.coverUrl ? '更换封面' : '上传封面' }}
        </button>
        <span v-if="form.coverUrl" class="cover-thumb-wrap">
          <img :src="form.coverUrl" class="cover-thumb" alt="封面预览" />
          <button class="cover-remove" type="button" @click="form.coverUrl = ''">×</button>
        </span>
      </div>
    </div>

    <!-- 编辑区 -->
    <div class="editor-main">
      <MdEditor
        v-model="form.content"
        :on-upload-img="handleUploadImg"
        :sanitize="sanitizeHtml"
        preview-theme="github"
        language="zh-CN"
        style="height: 100%"
      />
    </div>
  </div>
  </MainLayout>
</template>

<style scoped>
.editor-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #fff;
}

/* ---- 顶部工具栏 ---- */
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 20px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}

.editor-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #555;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.15s;
}
.editor-back:hover {
  background: #f3f4f6;
}

.editor-title-input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  font-size: 26px;
  font-weight: 800;
  color: #111;
  background: transparent;
}
.editor-title-input::placeholder {
  color: #c0c0c0;
  font-weight: 400;
}

.editor-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

.editor-btn {
  padding: 9px 22px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition:
    background 0.15s,
    opacity 0.15s;
  border: 0;
}
.editor-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.editor-btn-draft {
  color: #111;
  background: #f3f4f6;
}
.editor-btn-draft:hover:not(:disabled) {
  background: #e5e7eb;
}

.editor-btn-publish {
  color: #fff;
  background: #2f6f73;
}
.editor-btn-publish:hover:not(:disabled) {
  background: #25595d;
}

/* ---- 配置栏 ---- */
.editor-config {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 10px 20px;
  border-bottom: 1px solid #f0f0f0;
  background: #fafafa;
}

.config-field {
  display: flex;
  align-items: center;
  gap: 8px;
}
.config-field label {
  font-size: 13px;
  font-weight: 700;
  color: #555;
  white-space: nowrap;
}

.cover-upload-btn {
  padding: 6px 14px;
  border: 1px dashed #ccc;
  border-radius: 6px;
  background: #fff;
  color: #555;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.cover-upload-btn:hover:not(:disabled) {
  border-color: #2f6f73;
  color: #2f6f73;
}
.cover-upload-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.cover-thumb-wrap {
  position: relative;
  display: inline-flex;
}

.cover-thumb {
  width: 56px;
  height: 40px;
  border-radius: 4px;
  object-fit: cover;
  border: 1px solid #e5e7eb;
}

.cover-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 18px;
  height: 18px;
  border: 1px solid #ddd;
  border-radius: 50%;
  background: #fff;
  color: #999;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}
.cover-remove:hover {
  color: #e74c3c;
  border-color: #e74c3c;
}

/* ---- 编辑区 ---- */
.editor-main {
  flex: 1;
  min-height: 0;
}
</style>
