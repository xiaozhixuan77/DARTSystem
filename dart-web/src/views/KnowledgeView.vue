<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useKnowledgeStore, ALL_TAG } from '@/stores/knowledge'
import { useDebounce } from '@/composables/useDebounce'
import KnowledgeCard from '@/components/knowledge/KnowledgeCard.vue'
import KnowledgeGraphView from '@/components/knowledge/KnowledgeGraphView.vue'
import KnowledgeEditDialog from '@/components/knowledge/KnowledgeEditDialog.vue'

const router = useRouter()
const store = useKnowledgeStore()

const searchText = ref('')
const searchDebounced = useDebounce(() => searchText.value, 400)
const view = ref<'list' | 'graph'>('list')
const dialogVisible = ref(false)
const dialogInitialTab = ref<'content' | 'detail' | 'graph'>('content')

const totalPages = computed(() => Math.max(1, Math.ceil(store.total / store.size)))

watch(searchDebounced, (val) => store.setKeyword(val.trim()))

onMounted(() => {
  store.loadItems()
  store.loadTags()
})

async function openDetail(id: number, initialTab: 'content' | 'detail' | 'graph' = 'content') {
  await store.loadDetail(id)
  dialogInitialTab.value = initialTab
  dialogVisible.value = true
}

async function handleSave(data: { title: string; tags: string; notes: string; content: string }) {
  if (!store.selectedItem) return
  await store.saveItem(store.selectedItem.id, data)
  dialogVisible.value = false
  await store.loadItems()
  await store.loadTags()
}

function prevPage() {
  if (store.page <= 1) return
  store.page--
  store.loadItems()
}

function nextPage() {
  if (store.page >= totalPages.value) return
  store.page++
  store.loadItems()
}
</script>

<template>
  <div class="knowledge-page">
    <header class="page-header">
      <button class="back-btn" @click="router.push('/chat')">← 返回</button>
      <h1 class="page-title">知识库</h1>
      <input
        v-model="searchText"
        class="search-input"
        type="text"
        placeholder="搜索标题 / 标签 / 正文"
      />
      <div class="view-switch">
        <button class="view-btn" :class="{ active: view === 'list' }" @click="view = 'list'">列表</button>
        <button class="view-btn" :class="{ active: view === 'graph' }" @click="view = 'graph'">图谱</button>
      </div>
    </header>

    <div class="tag-bar">
      <button
        v-for="tag in [ALL_TAG, ...store.tags]"
        :key="tag"
        class="tag-chip"
        :class="{ active: store.filter === tag }"
        @click="store.setFilter(tag)"
      >{{ tag }}</button>
    </div>

    <main class="page-body">
      <div v-if="view === 'list'" class="list-view">
        <div class="card-grid">
          <KnowledgeCard v-for="item in store.items" :key="item.id" :item="item" @select="openDetail" />
        </div>
        <div v-if="store.items.length === 0 && !store.loading" class="page-empty">
          暂无知识条目，沉淀报告后自动入库
        </div>
        <div v-if="store.total > store.size" class="knowledge-pager">
          <button class="pager-btn" :disabled="store.page <= 1" @click="prevPage">上一页</button>
          <span class="pager-info">{{ store.page }} / {{ totalPages }}</span>
          <button class="pager-btn pager-next" :disabled="store.page >= totalPages" @click="nextPage">下一页</button>
        </div>
      </div>
      <KnowledgeGraphView v-else @select-item="(id: number) => openDetail(id, 'graph')" />
    </main>

    <KnowledgeEditDialog
      :visible="dialogVisible"
      :item="store.selectedItem"
      :initial-tab="dialogInitialTab"
      @save="handleSave"
      @close="dialogVisible = false"
    />
  </div>
</template>

<style lang="scss" scoped>
.knowledge-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: $color-bg;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 24px;
  border-bottom: 1px solid $color-border;
  background: $color-surface;
}
.back-btn {
  border: 1px solid $color-border;
  background: transparent;
  border-radius: $radius;
  padding: 6px 14px;
  font-size: 14px;
  cursor: pointer;
  &:hover { background: $color-bg; }
}
.page-title {
  font-family: $font-display;
  font-size: 20px;
  font-weight: 700;
  margin: 0;
}
.search-input {
  flex: 1;
  max-width: 360px;
  padding: 8px 14px;
  border: 1px solid $color-border;
  border-radius: $radius;
  font-size: 14px;
  outline: none;
  &:focus { border-color: $color-accent; }
}
.view-switch {
  display: flex;
  border: 1px solid $color-border;
  border-radius: $radius;
  overflow: hidden;
}
.view-btn {
  padding: 6px 18px;
  border: none;
  background: transparent;
  font-size: 14px;
  cursor: pointer;
  &.active {
    background: $color-accent;
    color: white;
  }
}
.tag-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding: 12px 24px;
  border-bottom: 1px solid $color-border;
}
.tag-chip {
  padding: 4px 14px;
  border: 1px solid $color-border;
  border-radius: 999px;
  background: $color-surface;
  font-size: 13px;
  cursor: pointer;
  &.active {
    background: $color-accent;
    color: white;
    border-color: $color-accent;
  }
}
.page-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
.page-empty {
  padding: 80px 0;
  text-align: center;
  color: $color-muted;
  font-size: 14px;
}
.knowledge-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 24px;
}
.pager-btn {
  padding: 6px 16px;
  border: 1px solid $color-border;
  border-radius: $radius;
  background: $color-surface;
  font-size: 14px;
  cursor: pointer;
  &:disabled { opacity: 0.4; cursor: default; }
}
.pager-info {
  font-size: 14px;
  color: $color-muted;
}
.graph-view {
  height: 100%;
}
.graph-hint {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed $color-border;
  border-radius: $radius;
  color: $color-muted;
  font-size: 14px;
}
</style>
