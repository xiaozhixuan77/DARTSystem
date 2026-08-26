<script setup lang="ts">
import { ref, watch, computed, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import type { EChartsType } from 'echarts/core'
import { ElMessageBox } from 'element-plus'
import { useKnowledgeStore } from '@/stores/knowledge'
import { useMarkdown } from '@/composables/useMarkdown'
import { buildMarkdownExport, downloadMarkdown } from '@/composables/useMarkdownExport'
import { buildGraphOption } from '@/utils/knowledgeGraph'
import type { KnowledgeItemDetail, GraphData } from '@/types/knowledge'

const props = withDefaults(defineProps<{
  visible: boolean
  item: KnowledgeItemDetail | null
  initialTab?: 'content' | 'detail' | 'graph'
}>(), { initialTab: 'content' })
const emit = defineEmits<{
  save: [data: { title: string; tags: string; notes: string; content: string }]
  close: []
}>()

const store = useKnowledgeStore()
const { render, renderMermaidDiagrams } = useMarkdown()

const title = ref('')
const notes = ref('')
const tagInput = ref('')
const content = ref('')
const activeTab = ref<'content' | 'detail' | 'graph'>('content')
const contentEl = ref<HTMLElement | null>(null)
const graphEl = ref<HTMLDivElement | null>(null)
let chart: EChartsType | null = null

const renderedContent = computed(() =>
  props.item?.content ? render(props.item.content) : '')

watch(() => props.item, (val) => {
  if (val) {
    title.value = val.title
    notes.value = val.notes || ''
    tagInput.value = val.tags || ''
    content.value = val.content || ''
    activeTab.value = props.initialTab
    disposeChart()
  }
}, { immediate: true })

watch(() => props.visible, (val) => {
  if (!val) {
    activeTab.value = 'content'
    disposeChart()
  }
})

function handleExport() {
  if (!props.item) return
  downloadMarkdown(props.item.title, buildMarkdownExport(props.item))
}

async function handleDelete() {
  if (!props.item) return
  try {
    await ElMessageBox.confirm(
      `确定删除知识条目「${props.item.title}」吗？删除后不可恢复。`,
      '删除条目',
      { confirmButtonText: '删除', type: 'warning' }
    )
    await store.removeItem(props.item.id)
    emit('close')
  } catch { /* 取消删除 */ }
}

const canSave = computed(() => !!props.item && title.value.trim().length > 0)

function handleSave() {
  if (!canSave.value) return
  emit('save', {
    title: title.value.trim(),
    notes: notes.value.trim(),
    tags: tagInput.value,
    content: content.value
  })
}

function handleOverlayClick(e: MouseEvent) {
  if ((e.target as HTMLElement).classList.contains('dialog-overlay')) {
    emit('close')
  }
}

async function switchTab(tab: 'content' | 'detail' | 'graph') {
  activeTab.value = tab
  if (tab === 'content' && props.item?.content) {
    await nextTick()
    if (contentEl.value) {
      await renderMermaidDiagrams(contentEl.value)
    }
  }
  if (tab === 'graph' && props.item) {
    disposeChart()
    await store.loadGraph(props.item.id)
    await nextTick()
    renderGraph()
  }
}

function renderGraph() {
  if (!graphEl.value || !store.graphData) return
  const data: GraphData = store.graphData
  chart = echarts.init(graphEl.value)
  chart.setOption(buildGraphOption(data.nodes, data.edges, { repulsion: 120, edgeLength: 100 }))
}

function disposeChart() {
  if (chart) {
    chart.dispose()
    chart = null
  }
}
</script>

<template>
  <div v-if="visible" class="dialog-overlay" @click="handleOverlayClick">
    <div class="dialog-box">
      <h3 class="dialog-title">知识条目</h3>

      <div class="dialog-tabs">
        <button class="dialog-tab tab-content" :class="{ active: activeTab === 'content' }"
                @click="switchTab('content')">正文</button>
        <button class="dialog-tab tab-detail" :class="{ active: activeTab === 'detail' }"
                @click="activeTab = 'detail'">编辑</button>
        <button class="dialog-tab tab-graph" :class="{ active: activeTab === 'graph' }"
                @click="switchTab('graph')">关系图</button>
      </div>

      <div v-if="activeTab === 'content'" class="content-tab">
        <div
          v-if="item && item.content"
          ref="contentEl"
          class="markdown content-preview"
          v-html="renderedContent"
        />
        <div v-else class="graph-hint">暂无正文</div>
      </div>

      <div v-else-if="activeTab === 'detail'" class="detail-tab">
        <div class="dialog-field">
          <label for="edit-title">标题</label>
          <input id="edit-title" v-model="title" type="text" />
        </div>

        <div class="dialog-field">
          <label for="edit-tags">标签（逗号分隔）</label>
          <input id="edit-tags" v-model="tagInput" type="text" placeholder="Go, 并发, 后端" />
        </div>

        <div class="dialog-field">
          <label for="edit-notes">备注</label>
          <textarea id="edit-notes" v-model="notes" rows="4" placeholder="添加备注..." />
        </div>

        <div class="dialog-field">
          <label for="edit-content">正文（Markdown）</label>
          <textarea id="edit-content" v-model="content" rows="12" class="content-editor"
                    placeholder="编辑正文内容..." />
        </div>
      </div>

      <div v-else class="graph-tab">
        <div v-if="store.graphLoading" class="graph-hint">图谱加载中...</div>
        <div v-else-if="!store.graphData || store.graphData.nodes.length === 0" class="graph-hint">
          暂无关联关系，沉淀新报告后将自动生成。
        </div>
        <div v-else ref="graphEl" class="graph-container"></div>
      </div>

      <div class="dialog-actions">
        <button class="export-btn" @click="handleExport">导出 Markdown</button>
        <button class="delete-btn" @click="handleDelete">删除</button>
        <div class="action-spacer"></div>
        <button class="cancel-btn" @click="emit('close')">取消</button>
        <button class="save-btn" :disabled="!canSave" @click="handleSave">保存</button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.dialog-overlay {
  position: fixed; inset: 0;
  z-index: 900;
  background: rgba(0, 0, 0, 0.4);
  display: flex; align-items: center; justify-content: center;
}
.dialog-box {
  width: 560px; max-height: 80vh;
  background: $color-surface;
  border-radius: $radius-lg;
  padding: 24px;
  overflow-y: auto;
}
.dialog-title {
  font-family: $font-display;
  font-size: 18px; font-weight: 700;
  margin: 0 0 16px;
}
.dialog-tabs {
  display: flex; gap: 8px;
  margin-bottom: 16px;
  border-bottom: 1px solid $color-border;
}
.dialog-tab {
  padding: 8px 16px;
  border: none;
  background: transparent;
  font-size: 14px; color: $color-muted;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  &.active {
    color: $color-accent;
    border-bottom-color: $color-accent;
    font-weight: 600;
  }
}
.content-preview {
  max-height: 420px;
  overflow-y: auto;
  border: 1px solid $color-border;
  border-radius: $radius;
  padding: 16px;
  font-size: 14px; line-height: 1.8;
}
.graph-container {
  width: 100%; height: 320px;
  border: 1px solid $color-border;
  border-radius: $radius;
}
.graph-hint {
  height: 320px;
  display: flex; align-items: center; justify-content: center;
  border: 1px dashed $color-border;
  border-radius: $radius;
  color: $color-muted; font-size: 13px;
}
.dialog-field {
  margin-bottom: 16px;
  label {
    display: block;
    font-size: 13px; color: $color-muted;
    margin-bottom: 6px;
  }
  input, textarea {
    width: 100%;
    padding: 10px 12px;
    border: 1px solid $color-border;
    border-radius: $radius;
    font: inherit; font-size: 14px;
    outline: none;
    &:focus { border-color: $color-accent; }
  }
  textarea { resize: vertical; }
}
.dialog-actions {
  display: flex; gap: 12px; justify-content: flex-end;
  margin-top: 8px;
}
.content-editor {
  font-family: monospace;
  line-height: 1.6;
}
.action-spacer {
  flex: 1;
}
.export-btn, .delete-btn, .cancel-btn, .save-btn {
  padding: 8px 20px;
  border-radius: $radius;
  font-size: 14px; cursor: pointer;
}
.export-btn {
  border: 1px solid $color-border;
  background: $color-surface;
  &:hover { background: $color-bg; }
}
.delete-btn {
  border: 1px solid #e74c3c;
  color: #e74c3c;
  background: $color-surface;
  &:hover { background: #fdecea; }
}
.cancel-btn {
  border: 1px solid $color-border;
  background: $color-surface;
  &:hover { background: $color-bg; }
}
.save-btn {
  border: none;
  background: $color-accent; color: white;
  &:disabled { opacity: 0.4; cursor: default; }
  &:not(:disabled):hover { background: $color-accent-dark; }
}
</style>
