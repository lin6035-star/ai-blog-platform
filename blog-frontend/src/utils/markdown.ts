import { marked, Renderer } from 'marked'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'
import { sanitizeHtml } from './sanitizeHtml'

// ============================================================
// marked + highlight.js 封装，用于 AI 消息的 Markdown 渲染
// 流程固定：Markdown → marked.parse → DOMPurify.sanitize → v-html
// ============================================================

marked.setOptions({
  breaks: true,
  gfm: true,
})

const renderer = new Renderer()
renderer.code = function (token: { text: string; lang?: string; raw: string }) {
  const lang = token.lang
  const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
  const highlighted = hljs.highlight(token.text, { language }).value
  return `<pre><code class="hljs language-${language}">${highlighted}</code></pre>`
}

export function renderMarkdown(content: string): string {
  if (!content) return ''
  return sanitizeHtml(marked.parse(content, { renderer }) as string)
}
