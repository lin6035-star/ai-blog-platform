import { Marked } from 'marked'

const MAX_SUMMARY_LENGTH = 25
const marked = new Marked()

function normalizeSpaces(value: string) {
  return value.replace(/\s+/g, ' ').trim()
}

function stopBeforeBlockingContent(content: string) {
  const stopPatterns = [
    /```/,
    /~~~/,
    /<img\b[^>]*>/i,
    /!\[[^\]]*]\([^)]*\)/,
    /!\[[^\]]*]\[[^\]]*]/,
  ]

  const stopAt = stopPatterns
    .map((pattern) => content.search(pattern))
    .filter((index) => index >= 0)
    .sort((a, b) => a - b)[0]

  return stopAt === undefined ? content : content.slice(0, stopAt)
}

function renderToVisibleText(content: string) {
  const html = marked.parse(content) as string
  const container = document.createElement('div')
  container.innerHTML = html
  return container.textContent ?? ''
}

export function extractArticleSummary(content: string, maxLen = MAX_SUMMARY_LENGTH) {
  const plain = normalizeSpaces(renderToVisibleText(stopBeforeBlockingContent(content)))

  if (plain.length <= maxLen) {
    return plain
  }

  return `${plain.slice(0, maxLen)}…`
}
