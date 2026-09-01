import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useKnowledgeStore } from '../knowledge'

vi.mock('@/api/knowledge', () => ({
  listKnowledge: vi.fn(),
  getKnowledgeDetail: vi.fn(),
  deleteKnowledge: vi.fn(),
  getKnowledgeGraph: vi.fn(),
  updateKnowledge: vi.fn(),
  getKnowledgeTags: vi.fn(),
  getGlobalKnowledgeGraph: vi.fn(),
  fetchEntities: vi.fn()
}))

import { listKnowledge, getKnowledgeDetail, deleteKnowledge, getKnowledgeGraph, updateKnowledge, getKnowledgeTags, getGlobalKnowledgeGraph, fetchEntities } from '@/api/knowledge'

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

  it('初始状态下列表为空', () => {
    const store = useKnowledgeStore()
    expect(store.items).toEqual([])
    expect(store.total).toBe(0)
    expect(store.page).toBe(1)
    expect(store.loading).toBe(false)
    expect(store.keyword).toBe('')
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
        tags: 'Go', scoreT: 4, scoreA: 5, scoreD: 3, scoreM: 4,
        notes: '', createdAt: '', updatedAt: ''
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
        id: 1, title: 'x', content: '', sourceUrl: '', tags: '',
        scoreT: 0, scoreA: 0, scoreD: 0, scoreM: 0,
        notes: '', createdAt: '', updatedAt: ''
      }
    })

    const store = useKnowledgeStore()
    await store.loadItems()
    await store.loadDetail(1)
    await store.removeItem(1)

    expect(store.selectedItem).toBeNull()
  })

  it('标签筛选重置分页并携带标签重新加载', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [], total: 0 }
    })

    const store = useKnowledgeStore()
    store.page = 5
    store.setFilter('Go')

    expect(store.filter).toBe('Go')
    expect(store.page).toBe(1)
    expect(listKnowledge).toHaveBeenCalledWith(1, 12, 'Go', undefined, undefined)
  })

  it('全部标签不携带过滤参数', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [], total: 0 }
    })

    const store = useKnowledgeStore()
    store.setFilter('全部')

    expect(listKnowledge).toHaveBeenCalledWith(1, 12, undefined, undefined, undefined)
  })

  it('设置搜索关键词重置分页并携带 keyword 加载', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [], total: 0 }
    })

    const store = useKnowledgeStore()
    store.page = 5
    store.setKeyword('Spring')

    expect(store.keyword).toBe('Spring')
    expect(store.page).toBe(1)
    expect(listKnowledge).toHaveBeenCalledWith(1, 12, undefined, 'Spring', undefined)
  })

  it('空搜索关键词不携带 keyword 参数', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [], total: 0 }
    })

    const store = useKnowledgeStore()
    store.setKeyword('')

    expect(listKnowledge).toHaveBeenCalledWith(1, 12, undefined, undefined, undefined)
  })

  it('加载标签填充 tags', async () => {
    vi.mocked(getKnowledgeTags).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: ['Go', 'Vue']
    })

    const store = useKnowledgeStore()
    await store.loadTags()

    expect(store.tags).toEqual(['Go', 'Vue'])
  })

  it('加载标签失败时 tags 保持空', async () => {
    vi.mocked(getKnowledgeTags).mockRejectedValue(new Error('Network error'))

    const store = useKnowledgeStore()
    await store.loadTags()

    expect(store.tags).toEqual([])
  })

  it('加载实体填充 entities', async () => {
    vi.mocked(fetchEntities).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: [
        { id: 1, name: 'Redis', type: 'concept', itemCount: 3 },
        { id: 2, name: 'Spring', type: 'topic', itemCount: 2 }
      ]
    })

    const store = useKnowledgeStore()
    await store.loadEntities()

    expect(store.entities).toHaveLength(2)
    expect(store.entities[0].name).toBe('Redis')
  })

  it('实体筛选时 listKnowledge 携带 entity 参数', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [mockKnowledgeItem()], total: 1 }
    })

    const store = useKnowledgeStore()
    await store.setEntityFilter('Redis')

    expect(store.entityFilter).toBe('Redis')
    expect(listKnowledge).toHaveBeenCalledWith(1, 12, undefined, undefined, 'Redis')
  })

  it('实体筛选清空时不携带 entity 参数', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [], total: 0 }
    })

    const store = useKnowledgeStore()
    await store.setEntityFilter(undefined)

    expect(store.entityFilter).toBeUndefined()
    expect(listKnowledge).toHaveBeenCalledWith(1, 12, undefined, undefined, undefined)
  })

  it('保存条目更新 items 和 selectedItem', async () => {
    vi.mocked(listKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { records: [mockKnowledgeItem()], total: 1 }
    })
    vi.mocked(getKnowledgeDetail).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: {
        id: 1, title: '旧标题', content: '# Markdown', sourceUrl: 'https://x.com',
        tags: 'Go', scoreT: 4, scoreA: 5, scoreD: 3, scoreM: 4,
        notes: '', createdAt: '', updatedAt: ''
      }
    })
    vi.mocked(updateKnowledge).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null, data: null as any
    })

    const store = useKnowledgeStore()
    await store.loadItems()
    await store.loadDetail(1)
    await store.saveItem(1, { title: '新标题', tags: 'Go,并发', notes: '笔记', content: '# 新正文' })

    expect(updateKnowledge).toHaveBeenCalledWith(1, { title: '新标题', tags: 'Go,并发', notes: '笔记', content: '# 新正文' })
    expect(store.items[0].title).toBe('新标题')
    expect(store.items[0].tags).toBe('Go,并发')
    expect(store.selectedItem!.title).toBe('新标题')
    expect(store.selectedItem!.content).toBe('# 新正文')
  })

  it('保存失败时抛出异常', async () => {
    vi.mocked(updateKnowledge).mockResolvedValue({
      code: 400, message: '请求参数错误', suggestion: null, data: null as any
    })

    const store = useKnowledgeStore()
    await expect(store.saveItem(1, { title: '', tags: '', notes: '', content: '' }))
      .rejects.toBeTruthy()
  })

  it('加载图谱填充 graphData', async () => {
    vi.mocked(getKnowledgeGraph).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: {
        nodes: [
          { id: 'item:1', label: 'Go 并发模式', category: 'knowledge' },
          { id: 'ext:Goroutine', label: 'Goroutine', category: 'external' }
        ],
        edges: [{ source: 'item:1', target: 'ext:Goroutine', label: 'depends_on' }]
      }
    })

    const store = useKnowledgeStore()
    await store.loadGraph(1)

    expect(store.graphData).not.toBeNull()
    expect(store.graphData!.nodes).toHaveLength(2)
    expect(store.graphData!.edges[0].label).toBe('depends_on')
    expect(store.graphLoading).toBe(false)
  })

  it('加载图谱失败时清空 graphData', async () => {
    vi.mocked(getKnowledgeGraph).mockRejectedValue(new Error('Network error'))

    const store = useKnowledgeStore()
    await store.loadGraph(1)

    expect(store.graphData).toBeNull()
    expect(store.graphLoading).toBe(false)
  })

  it('加载全局图谱填充 globalGraph', async () => {
    vi.mocked(getGlobalKnowledgeGraph).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: {
        nodes: [
          { id: 'item:1', label: 'Spring', category: 'knowledge' },
          { id: 'item:2', label: 'Spring Boot', category: 'knowledge' }
        ],
        edges: [{ source: 'item:1', target: 'item:2', label: 'related_to' }]
      }
    })

    const store = useKnowledgeStore()
    await store.loadGlobalGraph()

    expect(store.globalGraph).not.toBeNull()
    expect(store.globalGraph!.nodes).toHaveLength(2)
    expect(store.globalGraph!.edges[0].label).toBe('related_to')
    expect(store.globalGraphLoading).toBe(false)
  })

  it('加载全局图谱失败时清空 globalGraph', async () => {
    vi.mocked(getGlobalKnowledgeGraph).mockRejectedValue(new Error('Network error'))

    const store = useKnowledgeStore()
    await store.loadGlobalGraph()

    expect(store.globalGraph).toBeNull()
    expect(store.globalGraphLoading).toBe(false)
  })
})
