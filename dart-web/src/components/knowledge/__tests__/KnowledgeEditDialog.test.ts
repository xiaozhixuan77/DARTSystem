import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import KnowledgeEditDialog from '../KnowledgeEditDialog.vue'

describe('KnowledgeEditDialog.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const mockDetail = {
    id: 1, title: 'Go 并发', content: '# 内容',
    sourceUrl: 'https://go.dev', tags: ['Go', '并发'],
    scoreT: 4, scoreA: 5, scoreD: 3, scoreM: 4,
    notes: '学习笔记', chunks: [],
    createdAt: '2026-01-01', updatedAt: '2026-07-01'
  }

  it('不可见时不渲染', () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: false, item: null }
    })
    expect(wrapper.find('.dialog-overlay').exists()).toBe(false)
  })

  it('可见时渲染对话框', () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    expect(wrapper.find('.dialog-overlay').exists()).toBe(true)
  })

  it('显示标题字段', () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    const titleInput = wrapper.find('#edit-title')
    expect((titleInput.element as HTMLInputElement).value).toBe('Go 并发')
  })

  it('显示备注字段', () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    const notesInput = wrapper.find('#edit-notes')
    expect((notesInput.element as HTMLTextAreaElement).value).toBe('学习笔记')
  })

  it('保存触发 save 事件', async () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    await wrapper.find('.save-btn').trigger('click')
    expect(wrapper.emitted('save')).toBeTruthy()
    // 事件携带更新后的数据
  })

  it('取消触发 close 事件', async () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    await wrapper.find('.cancel-btn').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('点击遮罩关闭', async () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    await wrapper.find('.dialog-overlay').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('无条目时禁用保存', () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: null }
    })
    const saveBtn = wrapper.find('.save-btn')
    expect(saveBtn.attributes('disabled')).toBeDefined()
  })
})
