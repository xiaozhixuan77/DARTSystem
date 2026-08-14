import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useKnowledgeStore } from '@/stores/knowledge'
import KnowledgeView from '../KnowledgeView.vue'

vi.mock('@/api/knowledge', () => ({
  listKnowledge: vi.fn(),
  getKnowledgeDetail: vi.fn(),
  deleteKnowledge: vi.fn(),
  getKnowledgeGraph: vi.fn(),
  getGlobalKnowledgeGraph: vi.fn(),
  updateKnowledge: vi.fn(),
  getKnowledgeTags: vi.fn()
}))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { listKnowledge, getKnowledgeDetail, getKnowledgeTags } from '@/api/knowledge'

function mockList(records: any[] = [], total = 0) {
  vi.mocked(listKnowledge).mockResolvedValue({
    code: 200, message: 'ok', suggestion: null, data: { records, total }
  })
}

function mockItem(id = 1) {
  return {
    id, title: 'Go 并发模式', sourceUrl: 'https://go.dev', tags: 'Go',
    scoreT: 4, scoreA: 5, scoreD: 4, scoreM: 5, weightedScore: 4.6, createdAt: '2026-01-01'
  }
}

function mountView() {
  return mount(KnowledgeView, {
    global: {
      stubs: {
        KnowledgeCard: {
          template: '<div class="knowledge-card" @click="$emit(\'select\', item.id)"></div>',
          props: ['item']
        },
        KnowledgeEditDialog: {
          template: '<div v-if="visible" class="dialog-overlay"></div>',
          props: ['visible', 'item']
        }
      }
    }
  })
}

describe('KnowledgeView.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.useFakeTimers()
    mockList()
    vi.mocked(getKnowledgeTags).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: ['Go', 'Vue']
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('渲染头部：标题、搜索框、视图切换', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.page-title').text()).toBe('知识库')
    expect(wrapper.find('.search-input').exists()).toBe(true)
    expect(wrapper.find('.back-btn').exists()).toBe(true)
    expect(wrapper.findAll('.view-btn')).toHaveLength(2)
  })

  it('挂载时加载列表和标签', async () => {
    mountView()
    await flushPromises()

    expect(listKnowledge).toHaveBeenCalled()
    expect(getKnowledgeTags).toHaveBeenCalled()
  })

  it('渲染卡片列表', async () => {
    mockList([mockItem(), { ...mockItem(2), title: 'Vue 响应式' }], 2)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('.knowledge-card')).toHaveLength(2)
  })

  it('空列表显示空态', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.page-empty').text()).toContain('暂无知识条目')
  })

  it('渲染标签筛选 chips 并支持点击筛选', async () => {
    const wrapper = mountView()
    await flushPromises()

    const chips = wrapper.findAll('.tag-chip')
    expect(chips.length).toBeGreaterThanOrEqual(3)
    await chips[1].trigger('click')

    const store = useKnowledgeStore()
    expect(store.filter).toBe('Go')
    expect(listKnowledge).toHaveBeenLastCalledWith(1, 12, 'Go', undefined)
  })

  it('搜索输入防抖 400ms 后触发 setKeyword', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.search-input').setValue('Spring')
    expect(listKnowledge).not.toHaveBeenLastCalledWith(1, 12, undefined, 'Spring')

    await vi.advanceTimersByTimeAsync(400)
    await flushPromises()

    expect(listKnowledge).toHaveBeenLastCalledWith(1, 12, undefined, 'Spring')
  })

  it('总分页数超过一页时显示分页并支持翻页', async () => {
    mockList([mockItem()], 25)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.knowledge-pager').exists()).toBe(true)
    await wrapper.find('.pager-next').trigger('click')
    expect(listKnowledge).toHaveBeenLastCalledWith(2, 12, undefined, undefined)
  })

  it('点击卡片加载详情并打开弹窗', async () => {
    mockList([mockItem()], 1)
    vi.mocked(getKnowledgeDetail).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: {
        id: 1, title: 'Go 并发模式', content: '# Markdown', sourceUrl: 'https://go.dev',
        tags: 'Go', scoreT: 4, scoreA: 5, scoreD: 4, scoreM: 5,
        notes: '', chunks: [], createdAt: '', updatedAt: ''
      }
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.knowledge-card').trigger('click')
    await flushPromises()

    const store = useKnowledgeStore()
    expect(store.selectedItem).not.toBeNull()
    expect(wrapper.find('.dialog-overlay').exists()).toBe(true)
  })

  it('视图切换按钮切换列表/图谱视图', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.list-view').exists()).toBe(true)
    await wrapper.findAll('.view-btn')[1].trigger('click')

    expect(wrapper.find('.list-view').exists()).toBe(false)
    expect(wrapper.find('.graph-view').exists()).toBe(true)
  })
})
