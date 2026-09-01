import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { GraphNode, GraphEdge } from '@/types/knowledge'

echarts.use([GraphChart, TooltipComponent, CanvasRenderer])

export interface GraphOptionParams {
  repulsion?: number
  edgeLength?: number
}

/** 图数据 → ECharts graph series option（外部概念节点橙色，知识节点主色） */
export function buildGraphOption(
  nodes: GraphNode[],
  edges: GraphEdge[],
  params?: GraphOptionParams
) {
  const repulsion = params?.repulsion ?? 150
  const edgeLength = params?.edgeLength ?? 120
  return {
    tooltip: {},
    legend: { show: false },
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      label: { show: true, position: 'right', fontSize: 12 },
      force: { repulsion, edgeLength },
      data: nodes.map((n) => ({
        id: n.id,
        name: n.label,
        category: n.category,
        itemStyle: n.category === 'external'
          ? { color: '#ffa94d' }
          : n.category === 'entity'
            ? { color: '#67C23A' }
            : { color: '#5B5FEB' }
      })),
      links: edges.map((e) => ({
        source: e.source,
        target: e.target,
        label: { show: true, formatter: e.label, fontSize: 10 }
      })),
      categories: [{ name: 'knowledge' }, { name: 'external' }]
    }]
  }
}
