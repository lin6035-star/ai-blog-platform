import { sanitizeHtml } from './sanitizeHtml'

// ============================================================
// 搜索关键词高亮的**兜底**实现（纯文本匹配）
//
// 主路径是后端 ES 高亮（见 ArticleSearchService）：它走 IK 分词，和检索本身同一套切词，
// 所以"亮出来的词"与"实际命中的词"永远一致。
//
// 但后端高亮**只在 ES 可用时有值**——ES 挂了会降级回 LIKE，那时一个高亮都没有。
// 这个函数负责那种情况：至少让用户看到"我搜的词在标题哪里"，而不是一片纯文本。
//
// 已知局限（有意接受，不要在这里补）：只能匹配"查询串原样连续出现"。
// 标题是「Redis 缓存与缓存穿透」时，搜「缓存穿透」在这里匹配不到——
// 中文多词 / 非连续查询本来就该交给 ES 分词高亮。在这里做二次分词会造出
// 第二套与检索不一致的匹配逻辑，比"少亮一处"更糟。
// ============================================================

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 把文本里出现的关键词包成 `<em>`，其余内容做 HTML 转义。
 * **返回值已过 DOMPurify**，可以直接交给 `v-html`。
 *
 * 关键词为空或没匹配到时返回转义后的原文（不是空串）。
 */
export function highlightByKeyword(text: string, keyword: string): string {
  const escapedText = escapeHtml(text ?? '')
  const trimmed = keyword?.trim()
  if (!trimmed) {
    return sanitizeHtml(escapedText)
  }

  // 关键词同样是用户输入：先 HTML 转义（与正文保持同一套表示），再转义正则元字符
  const pattern = escapeRegExp(escapeHtml(trimmed))
  if (!pattern) {
    return sanitizeHtml(escapedText)
  }

  return sanitizeHtml(escapedText.replace(new RegExp(pattern, 'gi'), (match) => `<em>${match}</em>`))
}
