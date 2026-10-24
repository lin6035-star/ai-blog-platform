import { shallowMount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Comment } from '@/types/comment'
import CommentEditor from './CommentEditor.vue'
import CommentItem from './CommentItem.vue'

const comment: Comment = {
  id: '12',
  articleId: '1',
  userId: '2',
  nickname: '测试用户',
  avatarUrl: null,
  content: '评论内容',
  rootId: null,
  parentId: null,
  replyToNickname: null,
  ipLocation: '广东',
  likeCount: 0,
  liked: false,
  createdAt: '2026-07-14T10:00:00',
  replies: [],
  replyCount: 0,
}

describe('CommentItem inline reply editor', () => {
  it('toggles the reply target and renders the editor under the active comment', async () => {
    const wrapper = shallowMount(CommentItem, {
      props: {
        comment,
        currentUserId: 2,
        activeReplyId: null,
      },
      global: {
        stubs: {
          NIcon: true,
        },
      },
    })

    const replyButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('回复'))

    expect(replyButton).toBeDefined()
    await replyButton!.trigger('click')
    expect(wrapper.emitted('toggleReply')?.[0]).toEqual([comment])

    await wrapper.setProps({ activeReplyId: comment.id })

    expect(wrapper.find('.comment-inline-editor').exists()).toBe(true)
    expect(wrapper.text()).toContain('取消回复')

    const editor = wrapper.findComponent(CommentEditor)
    expect(editor.props('replyTarget')).toBe('测试用户')
    editor.vm.$emit('submit', '回复内容')
    expect(wrapper.emitted('submitReply')?.[0]).toEqual([comment, '回复内容'])
  })
})
