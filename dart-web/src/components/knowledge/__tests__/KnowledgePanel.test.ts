import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useKnowledgeStore } from '@/stores/knowledge'
import KnowledgePanel from '../KnowledgePanel.vue'

vi.mock('@/api/knowledge', () => ({
  listKnowledge: vi.fn(),
  getKnowledgeDetail: vi.fn(),
  deleteKnowledge: vi.fn()
}))

describe('KnowledgePanel.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('面板关闭时不显示（collapsed）', () => {
    const store = useKnowledgeStore()
    store.panelOpen = false

    const wrapper = mount(KnowledgePanel)
    expect(wrapper.find('.knowledge-panel.collapsed').exists()).toBe(true)
  })

  it('面板打开时显示标题', () => {
    const store = useKnowledgeStore()
    store.panelOpen = true

    const wrapper = mount(KnowledgePanel)
    expect(wrapper.find('.panel-title').text()).toBe('知识库')
  })

  it('渲染标签筛选按钮', () => {
    const store = useKnowledgeStore()
    store.panelOpen = true

    const wrapper = mount(KnowledgePanel)
    const chips = wrapper.findAll('.tag-chip')
    expect(chips).toHaveLength(5)
    expect(chips[0].text()).toBe('全部')
  })

  it('点击标签调用 setFilter', async () => {
    const store = useKnowledgeStore()
    store.panelOpen = true
    const filterSpy = vi.spyOn(store, 'setFilter')

    const wrapper = mount(KnowledgePanel)
    const chips = wrapper.findAll('.tag-chip')
    await chips[1].trigger('click') // Go

    expect(filterSpy).toHaveBeenCalledWith('Go')
  })

  it('有知识条目时渲染 KnowledgeCard', () => {
    const store = useKnowledgeStore()
    store.panelOpen = true
    store.items = [{
      id: 1, title: '测试',
      scoreT: 4, scoreA: 5, scoreD: 3, scoreM: 4,
      sourceUrl: 'https://x.com', tags: 'Go', createdAt: ''
    }]

    const wrapper = mount(KnowledgePanel)
    // KnowledgeCard 组件会被渲染
    expect(wrapper.findComponent({ name: 'KnowledgeCard' }).exists()).toBe(true)
  })

  it('无条目时显示空状态', () => {
    const store = useKnowledgeStore()
    store.panelOpen = true
    store.items = []
    store.loading = false

    const wrapper = mount(KnowledgePanel)
    expect(wrapper.find('.panel-empty').text()).toBe('暂无知识条目')
  })

  it('总数大于每页条数时显示分页', () => {
    const store = useKnowledgeStore()
    store.panelOpen = true
    store.total = 30
    store.size = 12

    const wrapper = mount(KnowledgePanel)
    expect(wrapper.find('.panel-pager').exists()).toBe(true)
  })

  it('总数小于每页条数时不显示分页', () => {
    const store = useKnowledgeStore()
    store.panelOpen = true
    store.total = 5
    store.size = 12

    const wrapper = mount(KnowledgePanel)
    expect(wrapper.find('.panel-pager').exists()).toBe(false)
  })

  it('点击关闭按钮隐藏面板', async () => {
    const store = useKnowledgeStore()
    store.panelOpen = true

    const wrapper = mount(KnowledgePanel)
    await wrapper.find('.panel-close').trigger('click')

    expect(store.panelOpen).toBe(false)
  })
})
