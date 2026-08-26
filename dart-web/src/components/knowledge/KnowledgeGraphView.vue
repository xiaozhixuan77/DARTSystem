<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import type { EChartsType } from 'echarts/core'
import { buildGraphOption } from '@/utils/knowledgeGraph'
import { useKnowledgeStore } from '@/stores/knowledge'

const emit = defineEmits<{ 'select-item': [id: number] }>()

const store = useKnowledgeStore()
const graphEl = ref<HTMLDivElement | null>(null)
let chart: EChartsType | null = null

function renderGraph() {
  if (!graphEl.value || !store.globalGraph) return
  if (!chart) {
    chart = echarts.init(graphEl.value)
    chart.on('click', (params) => {
      const nodeId = (params.data as { id?: string } | undefined)?.id
      if (nodeId && nodeId.startsWith('item:')) {
        emit('select-item', Number(nodeId.slice(5)))
      }
    })
  }
  chart.setOption(buildGraphOption(store.globalGraph.nodes, store.globalGraph.edges))
}

function handleResize() {
  chart?.resize()
}

onMounted(() => {
  store.loadGlobalGraph()
  window.addEventListener('resize', handleResize)
})

watch(() => store.globalGraph, () => {
  if (store.globalGraph && store.globalGraph.nodes.length > 0) {
    renderGraph()
  }
}, { immediate: true, flush: 'post' })

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<template>
  <div class="graph-view">
    <div v-if="store.globalGraphLoading" class="graph-hint">图谱加载中...</div>
    <div v-else-if="!store.globalGraph || store.globalGraph.nodes.length === 0" class="graph-hint">
      暂无知识关联，沉淀多份相关报告后自动生成。
    </div>
    <div v-else ref="graphEl" class="graph-container"></div>
  </div>
</template>

<style lang="scss" scoped>
.graph-view {
  height: 100%;
}
.graph-container {
  width: 100%;
  height: 100%;
  min-height: 400px;
  border: 1px solid $color-border;
  border-radius: $radius;
}
.graph-hint {
  height: 100%;
  min-height: 400px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed $color-border;
  border-radius: $radius;
  color: $color-muted;
  font-size: 14px;
}
</style>
