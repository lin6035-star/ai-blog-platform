// @vitest-environment node
// 本文件只读源码文本做断言，不需要 DOM；跑在 jsdom 下会让 node:fs 被 externalize 而收集失败
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// normalize CRLF：Windows 工作区 checkout 可能带 \r，避免换行断言因行尾格式误判
const editorView = readFileSync(resolve(__dirname, 'EditorView.vue'), 'utf8').replace(/\r\n/g, '\n')

describe('editor publish state actions', () => {
  it('hides draft save entry points when editing a published article', () => {
    expect(editorView).toContain('const isEditingPublished = computed(')
    expect(editorView).toContain('form.status === ARTICLE_STATUS.PUBLISHED')
    expect(editorView).toContain('v-if="!isEditingPublished"')
    expect(editorView).toContain("isEditingPublished ? '更新文章' : '发布'")
  })

  it('keeps published edits published when saving from shortcuts or leave guard', () => {
    expect(editorView).toContain('async function handleSavePublishedChange()')
    expect(editorView).toContain('saveArticle(ARTICLE_STATUS.PUBLISHED,')
    expect(editorView).toContain('isEditingPublished.value ? handleSavePublishedChange() : handleSaveDraft()')
    expect(editorView).toContain("isEditingPublished.value ? '保存并更新文章' : '保存草稿'")
    expect(editorView).toContain('你还有未保存的修改，是否保存并更新后再离开？')
    expect(editorView).toContain("isEditingPublished.value ? '取消' : '不保存'")
    expect(editorView).toContain(`onNegativeClick: () => {
        next()
      }`)
    expect(editorView).toContain(`onClose: () => {
        next(false)
      }`)
    expect(editorView).not.toContain('isEditingPublished.value ? next(false) : next()')
  })

  it('treats category changes as unsaved article edits', () => {
    expect(editorView).toContain('const initialCategoryId = ref<string | null>(null)')
    expect(editorView).toContain('form.categoryId !== initialCategoryId.value')
    expect(editorView).toContain('initialCategoryId.value = form.categoryId')
  })

  it('keeps the category selector empty by default but submits uncategorized articles as code-life', () => {
    expect(editorView).toContain('categoryId: null')
    expect(editorView).toContain('placeholder="选择分类"')
    expect(editorView).toContain("const DEFAULT_CATEGORY_CODE = 'code-life'")
    expect(editorView).toContain("const DEFAULT_CATEGORY_NAME = '代码人生'")
    expect(editorView).toContain('function resolveSubmitCategoryId()')
    expect(editorView).toContain('category.code === DEFAULT_CATEGORY_CODE')
    expect(editorView).toContain('category.name === DEFAULT_CATEGORY_NAME')
    expect(editorView).toContain('const submitForm: ArticleForm = {')
    expect(editorView).toContain('categoryId: resolveSubmitCategoryId(),')
    expect(editorView).toContain('myArticleApi.update(editId.value, submitForm)')
    expect(editorView).toContain('myArticleApi.create(submitForm)')
    expect(editorView).not.toContain('form.categoryId = resolveSubmitCategoryId()')
  })
})
