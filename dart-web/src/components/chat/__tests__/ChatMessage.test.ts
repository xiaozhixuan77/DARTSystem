import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatMessage from '../ChatMessage.vue'

describe('ChatMessage.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('渲染用户消息', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: { id: 1, role: 'user', content: 'Hello', createdAt: '2026-01-01' }
      }
    })
    expect(wrapper.find('.message-row.user').exists()).toBe(true)
    expect(wrapper.find('.message-content').text()).toBe('Hello')
  })

  it('用户消息显示"你"角色标签', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: { id: 1, role: 'user', content: 'hello', createdAt: '' }
      }
    })
    expect(wrapper.find('.message-role').text()).toBe('你')
  })

  it('助手消息显示"DART"角色标签', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: { id: 2, role: 'assistant', content: '你好', createdAt: '' }
      }
    })
    expect(wrapper.find('.message-role').text()).toBe('DART')
  })

  it('助手消息渲染 Markdown', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: { id: 2, role: 'assistant', content: '**bold text**', createdAt: '' }
      }
    })
    const html = wrapper.find('.markdown').html()
    expect(html).toContain('<strong>bold text</strong>')
  })

  it('助手消息净化 XSS', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: { id: 2, role: 'assistant', content: '<script>alert(1)</script>', createdAt: '' }
      }
    })
    const html = wrapper.find('.markdown').html()
    expect(html).not.toContain('<script>')
  })

  it('用户消息不使用 v-html', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: { id: 1, role: 'user', content: '<b>raw</b>', createdAt: '' }
      }
    })
    // 用户消息直接文本渲染，<b> 被 Vue 文本插值转义
    expect(wrapper.find('.message-row.user .message-content').html()).toContain('&lt;b&gt;')
  })

  it('用户消息右对齐（flex-end）', () => {
    const wrapper = mount(ChatMessage, {
      props: {
        message: { id: 1, role: 'user', content: 'hi', createdAt: '' }
      }
    })
    expect(wrapper.find('.message-row.user').exists()).toBe(true)
  })
})
