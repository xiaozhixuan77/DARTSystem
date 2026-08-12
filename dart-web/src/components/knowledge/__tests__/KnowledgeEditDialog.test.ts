import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import KnowledgeEditDialog from '../KnowledgeEditDialog.vue'

vi.mock('echarts/core', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), dispose: vi.fn() })),
  use: vi.fn()
}))
vi.mock('echarts/charts', () => ({ GraphChart: {} }))
vi.mock('echarts/components', () => ({ TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

vi.mock('@/api/knowledge', () => ({
  listKnowledge: vi.fn(),
  getKnowledgeDetail: vi.fn(),
  deleteKnowledge: vi.fn(),
  getKnowledgeGraph: vi.fn(() => Promise.resolve({
    code: 200, message: 'ok', suggestion: null,
    data: {
      nodes: [
        { id: 'item:1', label: 'Go 并发模式', category: 'knowledge' },
        { id: 'ext:Goroutine', label: 'Goroutine', category: 'external' }
      ],
      edges: [{ source: 'item:1', target: 'ext:Goroutine', label: 'depends_on' }]
    }
  }))
}))

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

  it('默认显示正文 tab', () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    expect(wrapper.find('.content-tab').exists()).toBe(true)
    expect(wrapper.find('.detail-tab').exists()).toBe(false)
    expect(wrapper.find('.graph-tab').exists()).toBe(false)
  })

  it('正文 tab 渲染 Markdown 内容', () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: { ...mockDetail, content: '**加粗内容**' } }
    })
    // useMarkdown 真实渲染；happy-dom 下 DOMPurify 会剥离 h1/pre，strong 保留
    expect(wrapper.find('.content-tab .markdown').html()).toContain('<strong>加粗内容</strong>')
  })

  it('空正文显示占位提示', () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: { ...mockDetail, content: '' } }
    })
    expect(wrapper.find('.content-tab').text()).toContain('暂无正文')
  })

  it('切换到编辑 tab 显示标题字段', async () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    await wrapper.find('.tab-detail').trigger('click')

    const titleInput = wrapper.find('#edit-title')
    expect((titleInput.element as HTMLInputElement).value).toBe('Go 并发')
  })

  it('编辑 tab 显示备注字段', async () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    await wrapper.find('.tab-detail').trigger('click')

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

  it('显示正文/编辑/关系图三个 tab', () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    expect(wrapper.find('.tab-content').exists()).toBe(true)
    expect(wrapper.find('.tab-detail').exists()).toBe(true)
    expect(wrapper.find('.tab-graph').exists()).toBe(true)
  })

  it('切换到关系图 tab 渲染图容器', async () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    await wrapper.find('.tab-graph').trigger('click')
    await flushPromises()

    expect(wrapper.find('.graph-tab').exists()).toBe(true)
  })

  it('关闭后重置为正文 tab', async () => {
    const wrapper = mount(KnowledgeEditDialog, {
      props: { visible: true, item: mockDetail }
    })
    await wrapper.find('.tab-detail').trigger('click')
    expect(wrapper.find('.detail-tab').exists()).toBe(true)

    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true, item: mockDetail })
    expect(wrapper.find('.content-tab').exists()).toBe(true)
  })
})
