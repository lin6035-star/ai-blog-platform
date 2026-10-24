// @vitest-environment node
// 本文件只读源码文本做断言，不需要 DOM；跑在 jsdom 下会让 node:fs 被 externalize 而收集失败
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const srcRoot = resolve(__dirname, '..')

function readSource(path: string) {
  return readFileSync(resolve(srcRoot, path), 'utf8')
}

describe('article detail comment section', () => {
  it('defines comment types and keeps comment requests behind api/comment.ts', () => {
    const typeSource = readSource('types/comment.ts')
    const apiSource = readSource('api/comment.ts')

    expect(typeSource).toContain('export interface Comment')
    expect(typeSource).toContain('replyToNickname?: string | null')
    expect(typeSource).toContain('replyCount: number')
    expect(typeSource).toContain('replies: Comment[]')
    expect(apiSource).toContain('export const commentApi')
    expect(apiSource).toContain("request.get<PageData<Comment>>(`/articles/${articleId}/comments`")
    expect(apiSource).toContain("request.get<PageData<Comment>>(`/comments/${rootId}/replies`")
    expect(apiSource).toContain("request.post<void>(`/articles/${articleId}/comments`, payload)")
    expect(apiSource).toContain("request.delete<void>(`/comments/${commentId}`)")
    expect(apiSource).toContain("request.post<void>(`/comments/${commentId}/like`)")
    expect(apiSource).toContain("sort?: CommentSort")
  })

  it('renders the comment section after article content on the detail page', () => {
    const detail = readSource('views/ArticleDetailView.vue')

    expect(detail).toContain("import CommentSection from '@/components/comment/CommentSection.vue'")
    expect(detail).toContain(':article-author-id="article.authorId"')
  })

  it('loads comments and connects publish, like and delete actions', () => {
    const section = readSource('components/comment/CommentSection.vue')
    const editor = readSource('components/comment/CommentEditor.vue')

    expect(section).toContain('commentApi.getList')
    expect(section).toContain("sort: selectedSort.value")
    expect(section).toContain('function loadMoreComments()')
    expect(section).toContain('comments.value = [...comments.value, ...result.data.list]')
    expect(section).toContain('commentApi.create')
    expect(section).toContain('commentApi.like')
    expect(section).toContain('commentApi.unlike')
    expect(section).toContain('commentApi.delete')
    expect(section).toContain('commentApi.getReplies')
    expect(section).toContain('parentId: null')
    expect(section).toContain('async function handleReplySubmit')
    expect(section).toContain('activeReplyId')
    expect(section).toContain('@submit-reply="handleReplySubmit"')
    expect(section).not.toContain(':reply-target="replyTarget?.nickname"')
    expect(section).not.toContain('后端发布评论接口完成后就能接入')
    expect(section).not.toContain('后端评论点赞接口完成后就能接入')
    expect(section).not.toContain('后端删除评论接口完成后就能接入')
    expect(editor).toContain('const emit = defineEmits')
    expect(editor).toContain('登录后发表评论')
    expect(editor).toContain('@click="handleSubmit"')
    expect(editor).toContain(':loading="submitting"')
  })

  it('loads more main comments when the bottom trigger enters the viewport', () => {
    const section = readSource('components/comment/CommentSection.vue')

    expect(section).toContain('const commentLoadMoreTriggerRef = ref<HTMLElement | null>(null)')
    expect(section).toContain('let commentObserver: IntersectionObserver | null = null')
    expect(section).toContain('new IntersectionObserver')
    expect(section).toContain('handleCommentLoadMoreIntersect')
    expect(section).toContain('commentObserver.observe(commentLoadMoreTriggerRef.value)')
    expect(section).toContain('ref="commentLoadMoreTriggerRef"')
    expect(section).toContain('class="comment-load-more-trigger"')
    expect(section).toContain('正在加载更多评论...')
    expect(section).toContain('继续下滑查看更多评论')
    expect(section).not.toContain('@click="loadMoreComments"')
    expect(section).not.toContain('>加载更多</n-button>')
  })

  it('shows flat reply threads with author, time, like count and ip location', () => {
    const item = readSource('components/comment/CommentItem.vue')

    expect(item).toContain('replyToNickname')
    expect(item).toContain('ipLocation')
    expect(item).toContain('likeCount')
    expect(item).toContain('comment.replies')
    expect(item).toContain('class="comment-replies"')
    expect(item).toContain('formatCommentTime')
    expect(item).toContain('articleAuthorId')
    expect(item).toContain("emit('loadReplies', comment)")
    expect(item).toContain("import CommentEditor from './CommentEditor.vue'")
    expect(item).toContain('class="comment-inline-editor"')
    expect(item).toContain('props.activeReplyId === props.comment.id')
    expect(item).toContain("emit('submitReply', comment, $event)")
  })
})
