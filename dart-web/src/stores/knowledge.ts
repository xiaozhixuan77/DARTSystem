import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listKnowledge, getKnowledgeDetail, deleteKnowledge, getKnowledgeGraph, getGlobalKnowledgeGraph, getKnowledgeTags, updateKnowledge, fetchEntities } from '@/api/knowledge'
import type { KnowledgeItemVO, KnowledgeItemDetail, GraphData, KnowledgeEntityVO } from '@/types/knowledge'

export const ALL_TAG = '全部'

export const useKnowledgeStore = defineStore('knowledge', () => {
  const items = ref<KnowledgeItemVO[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(12)
  const selectedItem = ref<KnowledgeItemDetail | null>(null)
  const filter = ref<string>('全部')
  const keyword = ref('')
  const loading = ref(false)
  const graphData = ref<GraphData | null>(null)
  const graphLoading = ref(false)
  const globalGraph = ref<GraphData | null>(null)
  const globalGraphLoading = ref(false)
  const tags = ref<string[]>([])
  const entities = ref<KnowledgeEntityVO[]>([])
  const entityFilter = ref<string | undefined>(undefined)

  async function loadItems() {
    loading.value = true
    try {
      const res = await listKnowledge(page.value, size.value, filter.value === ALL_TAG ? undefined : filter.value, keyword.value || undefined, entityFilter.value)
      if (res.code === 200) {
        items.value = res.data.records
        total.value = res.data.total
      }
    } catch { /* ignore */ }
    finally { loading.value = false }
  }

  async function loadEntities() {
    try {
      const res = await fetchEntities()
      // 按关联条目数降序（并列按 id 升序稳定）：实体云默认露出最精确 Top 2
      if (res.code === 200) {
        entities.value = [...res.data].sort((a, b) =>
          b.itemCount - a.itemCount || a.id - b.id)
      }
    } catch { /* ignore */ }
  }

  async function setEntityFilter(entity: string | undefined) {
    entityFilter.value = entity
    page.value = 1
    await loadItems()
  }

  async function loadTags() {
    try {
      const res = await getKnowledgeTags()
      if (res.code === 200) tags.value = res.data
    } catch { /* ignore */ }
  }

  async function saveItem(id: number, data: { title: string; tags: string; notes: string; content: string }) {
    const res = await updateKnowledge(id, data)
    if (res.code !== 200) throw new Error(res.message || '保存失败')
    const item = items.value.find((i) => i.id === id)
    if (item) {
      item.title = data.title
      item.tags = data.tags
    }
    if (selectedItem.value?.id === id) {
      selectedItem.value.title = data.title
      selectedItem.value.tags = data.tags || ''
      selectedItem.value.notes = data.notes
      selectedItem.value.content = data.content
    }
  }

  async function loadDetail(id: number) {
    const res = await getKnowledgeDetail(id)
    if (res.code === 200) selectedItem.value = res.data
  }

  async function removeItem(id: number) {
    await deleteKnowledge(id)
    items.value = items.value.filter((i) => i.id !== id)
    if (selectedItem.value?.id === id) selectedItem.value = null
  }

  function setFilter(tag: string) {
    filter.value = tag
    page.value = 1
    loadItems()
  }

  function setKeyword(kw: string) {
    keyword.value = kw
    page.value = 1
    loadItems()
  }

  async function loadGraph(id: number) {
    graphLoading.value = true
    try {
      const res = await getKnowledgeGraph(id)
      if (res.code === 200) graphData.value = res.data
    } catch {
      graphData.value = null
    }
    finally {
      graphLoading.value = false
    }
  }

  async function loadGlobalGraph() {
    globalGraphLoading.value = true
    try {
      const res = await getGlobalKnowledgeGraph()
      if (res.code === 200) globalGraph.value = res.data
    } catch {
      globalGraph.value = null
    }
    finally {
      globalGraphLoading.value = false
    }
  }

  return {
    items, total, page, size, selectedItem, filter, keyword,
    loading, graphData, graphLoading, globalGraph, globalGraphLoading, tags,
    entities, entityFilter,
    loadItems, loadTags, loadDetail, removeItem, loadGraph, loadGlobalGraph, saveItem,
    setFilter, setKeyword, loadEntities, setEntityFilter
  }
})
