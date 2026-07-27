import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useKnowledgeStore } from '../knowledge'

vi.mock('@/api/knowledge', () => ({
  listKnowledge: vi.fn(),
  getKnowledgeDetail: vi.fn(),
  deleteKnowledge: vi.fn()
}))

import { listKnowledge, getKnowledgeDetail, deleteKnowledge } from '@/api/knowledge'

function mockKnowledgeItem(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, title: 'Go 并发模式', sourceUrl: 'https://go.dev', tags: 'Go',
    scoreT: 4, scoreA: 5, scoreD: 4, scoreM: 5, weightedScore: 4.6, createdAt: '2026-01-01'
  }
}

describe('useKnowledgeStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('初始状态下列表为空且面板关闭', () => {
    const store = useKnowledgeStore()
    expect(store.items).toEqual([])
    expect(store.total).toBe(0)
    expect(store.page).toBe(1)
    expect(store.loading).toBe(false)
    expect(store.panelOpen).toBe(false)
    expect(store.selectedItem).toBeNull()
  })

  it('加载知识列表填充 items 和 total', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [mockKnowledgeItem()], total: 1 }
    })

    const store = useKnowledgeStore()
    await store.loadItems()

    expect(store.items).toHaveLength(1)
    expect(store.items[0].title).toBe('Go 并发模式')
    expect(store.total).toBe(1)
    expect(store.loading).toBe(false)
  })

  it('加载列表失败时保持原状态', async () => {
    vi.mocked(listKnowledge).mockRejectedValue(new Error('Network error'))

    const store = useKnowledgeStore()
    await store.loadItems()

    expect(store.items).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('加载知识详情设置 selectedItem', async () => {
    vi.mocked(getKnowledgeDetail).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: {
        id: 1, title: '详情标题', content: '# Markdown', sourceUrl: 'https://x.com',
        tags: ['Go'], scoreT: 4, scoreA: 5, scoreD: 3, scoreM: 4,
        notes: '', chunks: [], createdAt: '', updatedAt: ''
      }
    })

    const store = useKnowledgeStore()
    await store.loadDetail(1)

    expect(store.selectedItem).not.toBeNull()
    expect(store.selectedItem!.title).toBe('详情标题')
    expect(store.selectedItem!.content).toBe('# Markdown')
  })

  it('删除知识条目从列表中移除', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [mockKnowledgeItem(), { ...mockKnowledgeItem(), id: 2, title: 'Vue 响应式' }], total: 2 }
    })
    vi.mocked(deleteKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: null as any
    })

    const store = useKnowledgeStore()
    await store.loadItems()
    await store.removeItem(1)

    expect(store.items).toHaveLength(1)
    expect(store.items[0].id).toBe(2)
  })

  it('删除当前选中条目时清空 selectedItem', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [mockKnowledgeItem()], total: 1 }
    })
    vi.mocked(deleteKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: null as any
    })
    vi.mocked(getKnowledgeDetail).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: {
        id: 1, title: 'x', content: '', sourceUrl: '', tags: [],
        scoreT: 0, scoreA: 0, scoreD: 0, scoreM: 0,
        notes: '', chunks: [], createdAt: '', updatedAt: ''
      }
    })

    const store = useKnowledgeStore()
    await store.loadItems()
    await store.loadDetail(1)
    await store.removeItem(1)

    expect(store.selectedItem).toBeNull()
  })

  it('标签筛选重置分页并重新加载', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [], total: 0 }
    })

    const store = useKnowledgeStore()
    store.page = 5
    store.setFilter('Go')

    expect(store.filter).toBe('Go')
    expect(store.page).toBe(1)
  })

  it('面板开关切换状态', () => {
    const store = useKnowledgeStore()
    expect(store.panelOpen).toBe(false)

    store.togglePanel()
    expect(store.panelOpen).toBe(true)

    store.togglePanel()
    expect(store.panelOpen).toBe(false)
  })
})
