<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useKnowledgeStore, ALL_TAG } from '@/stores/knowledge'
import KnowledgeCard from './KnowledgeCard.vue'
import KnowledgeEditDialog from './KnowledgeEditDialog.vue'

const store = useKnowledgeStore()

onMounted(() => {
  store.loadItems()
  store.loadTags()
})

const tags = computed(() => [ALL_TAG, ...store.tags])

function handleCloseDetail() {
  store.selectedItem = null
}

async function handleSave(data: { title: string; tags: string; notes: string }) {
  if (!store.selectedItem) return
  try {
    await store.saveItem(store.selectedItem.id, data)
    handleCloseDetail()
    store.loadItems()
    store.loadTags()
  } catch {
    // 保存失败保持弹窗打开，由用户修正后重试
  }
}
</script>

<template>
  <aside class="knowledge-panel" :class="{ collapsed: !store.panelOpen }">
    <div class="panel-header">
      <h3 class="panel-title">知识库</h3>
      <div class="panel-actions">
        <span class="panel-count" v-if="store.total">共 {{ store.total }} 条</span>
        <button class="panel-close" @click="store.panelOpen = false">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
            <path d="M18 6L6 18M6 6l12 12"/>
          </svg>
        </button>
      </div>
    </div>

    <div class="panel-tags">
      <button
        v-for="tag in tags" :key="tag"
        class="tag-chip"
        :class="{ active: store.filter === tag }"
        @click="store.setFilter(tag)"
      >{{ tag }}</button>
    </div>

    <div class="panel-grid">
      <KnowledgeCard
        v-for="item in store.items" :key="item.id"
        :item="item"
        @select="(id) => store.loadDetail(id)"
      />
      <div v-if="store.items.length === 0 && !store.loading" class="panel-empty">
        暂无知识条目
      </div>
    </div>

    <div class="panel-pager" v-if="store.total > store.size">
      <button @click="store.page--; store.loadItems()" :disabled="store.page <= 1">上一页</button>
      <span>第 {{ store.page }} / {{ Math.ceil(store.total / store.size) }} 页</span>
      <button @click="store.page++; store.loadItems()" :disabled="store.page >= Math.ceil(store.total / store.size)">下一页</button>
    </div>
  </aside>

  <KnowledgeEditDialog
    :visible="!!store.selectedItem"
    :item="store.selectedItem"
    @save="handleSave"
    @close="handleCloseDetail"
  />
</template>

<style lang="scss" scoped>
.knowledge-panel {
  width: $panel-width;
  height: 100vh;
  display: flex; flex-direction: column;
  background: $color-surface;
  border-left: 1px solid $color-border;
  flex-shrink: 0;
  transition: width $duration-enter $ease-out;
  overflow: hidden;
  &.collapsed { width: 0; padding: 0; border: none; }
}
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: $gap-md $gap-md 0;
}
.panel-title {
  font-family: $font-display;
  font-size: 18px; font-weight: 700;
  margin: 0;
}
.panel-actions { display: flex; align-items: center; gap: 8px; }
.panel-count { font-size: 12px; color: $color-muted; }
.panel-close {
  border: none; background: none; cursor: pointer; color: $color-muted;
  padding: 4px;
  &:hover { color: $color-danger; }
}
.panel-tags {
  display: flex; gap: 6px; padding: $gap-md; flex-wrap: wrap;
}
.tag-chip {
  padding: 4px 12px;
  border: 1px solid $color-border;
  border-radius: 100px;
  font-size: 12px;
  cursor: pointer;
  background: $color-surface;
  transition: all $duration-fast;
  &.active { background: $color-accent; color: white; border-color: $color-accent; }
  &:hover:not(.active) { border-color: $color-accent; }
}
.panel-grid {
  flex: 1; overflow-y: auto;
  padding: 0 $gap-md $gap-md;
  display: flex; flex-direction: column; gap: 10px;
}
.panel-empty {
  text-align: center; color: $color-muted;
  padding: $gap-xl; font-size: 13px;
}
.panel-pager {
  display: flex; align-items: center; justify-content: center; gap: $gap-md;
  padding: $gap-md;
  border-top: 1px solid $color-border;
  font-size: 12px;
  button {
    padding: 4px 10px;
    border: 1px solid $color-border;
    border-radius: $radius;
    background: $color-surface;
    font-size: 12px;
    cursor: pointer;
    &:disabled { opacity: 0.4; cursor: default; }
    &:not(:disabled):hover { border-color: $color-accent; }
  }
}
</style>
