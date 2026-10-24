import { Marked, Renderer, type Tokens } from 'marked'
import { sanitizeHtml } from './sanitizeHtml'

export interface ArticleOutlineItem {
  id: string
  text: string
  level: number
}

export interface RenderedArticleWithOutline {
  html: string
  outline: ArticleOutlineItem[]
}

function escapeAttribute(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function createHeadingId(text: string, usedIds: Map<string, number>) {
  const base =
    text
      .trim()
      .toLowerCase()
      .replace(/[^\p{Letter}\p{Number}_-]+/gu, '-')
      .replace(/^-+|-+$/g, '') || 'section'
  const count = usedIds.get(base) || 0
  usedIds.set(base, count + 1)

  return count === 0 ? `heading-${base}` : `heading-${base}-${count + 1}`
}

export function renderArticleWithOutline(markdown: string): RenderedArticleWithOutline {
  const outline: ArticleOutlineItem[] = []
  const usedIds = new Map<string, number>()
  const renderer = new Renderer()

  renderer.heading = function heading(token: Tokens.Heading) {
    const headingHtml = this.parser.parseInline(token.tokens)
    const shouldShowInOutline = token.depth >= 2 && token.depth <= 4

    if (!shouldShowInOutline) {
      return `<h${token.depth}>${headingHtml}</h${token.depth}>\n`
    }

    const id = createHeadingId(token.text, usedIds)
    outline.push({
      id,
      text: token.text,
      level: token.depth,
    })

    return `<h${token.depth} id="${escapeAttribute(id)}">${headingHtml}</h${token.depth}>\n`
  }

  const marked = new Marked({ renderer })

  return {
    html: sanitizeHtml(marked.parse(markdown) as string),
    outline,
  }
}
