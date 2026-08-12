import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listKnowledge, getKnowledgeDetail, deleteKnowledge, getKnowledgeGraph, getKnowledgeTags, updateKnowledge } from '@/api/knowledge'
import type { KnowledgeItemVO, KnowledgeItemDetail, GraphData } from '@/types/knowledge'

export const useKnowledgeStore = defineStore('knowledge', () => {
  const items = ref<KnowledgeItemVO[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(12)
  const selectedItem = ref<KnowledgeItemDetail | null>(null)
  const filter = ref<string>('全部')
  const keyword = ref('')
  const loading = ref(false)
  const panelOpen = ref(false)
  const graphData = ref<GraphData | null>(null)
  const graphLoading = ref(false)
  const tags = ref<string[]>([])

  async function loadItems() {
    loading.value = true
    try {
      const res = await listKnowledge(page.value, size.value, filter.value === '全部' ? undefined : filter.value)
      if (res.code === 200) {
        items.value = res.data.records
        total.value = res.data.total
      }
    } catch { /* ignore */ }
    finally { loading.value = false }
  }

  async function loadTags() {
    try {
      const res = await getKnowledgeTags()
      if (res.code === 200) tags.value = res.data
    } catch { /* ignore */ }
  }

  async function saveItem(id: number, data: { title: string; tags: string; notes: string }) {
    const res = await updateKnowledge(id, data)
    if (res.code !== 200) throw new Error(res.message || '保存失败')
    const item = items.value.find((i) => i.id === id)
    if (item) {
      item.title = data.title
      item.tags = data.tags
    }
    if (selectedItem.value?.id === id) {
      selectedItem.value.title = data.title
      selectedItem.value.tags = (data.tags || '').split(',').map((t) => t.trim()).filter(Boolean)
      selectedItem.value.notes = data.notes
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

  function togglePanel() {
    panelOpen.value = !panelOpen.value
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

  return {
    items, total, page, size, selectedItem, filter, keyword,
    loading, panelOpen, graphData, graphLoading, tags,
    loadItems, loadTags, loadDetail, removeItem, loadGraph, saveItem,
    setFilter, togglePanel
  }
})
