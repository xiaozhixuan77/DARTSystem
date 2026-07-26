import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listKnowledge, getKnowledgeDetail, deleteKnowledge } from '@/api/knowledge'
import type { KnowledgeItemVO, KnowledgeItemDetail } from '@/types/knowledge'

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

  async function loadItems() {
    loading.value = true
    try {
      const res = await listKnowledge(page.value, size.value)
      if (res.code === 200) {
        items.value = res.data.records
        total.value = res.data.total
      }
    } catch { /* ignore */ }
    finally { loading.value = false }
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

  return {
    items, total, page, size, selectedItem, filter, keyword,
    loading, panelOpen, loadItems, loadDetail, removeItem,
    setFilter, togglePanel
  }
})
