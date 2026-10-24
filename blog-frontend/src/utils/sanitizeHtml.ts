import DOMPurify from 'dompurify'

// ============================================================
// 统一 HTML 清洗出口：所有 Markdown 渲染（marked → sanitize → v-html）
// 都必须经过这里，不要在 Vue 组件里各自清洗，避免新增渲染位置漏掉。
// ============================================================

export function sanitizeHtml(html: string): string {
  if (!html) return ''
  return DOMPurify.sanitize(html)
}
