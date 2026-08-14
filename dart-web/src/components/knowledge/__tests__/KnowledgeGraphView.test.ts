import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import KnowledgeGraphView from '../KnowledgeGraphView.vue'

const { initMock, setOptionMock, clickHandlerRef } = vi.hoisted(() => {
  const clickHandlerRef: { current: ((params: any) => void) | null } = { current: null }
  const setOptionMock = vi.fn()
  const initMock = vi.fn(() => ({
    setOption: setOptionMock,
    dispose: vi.fn(),
    on: vi.fn((_event: string, handler: (params: any) => void) => { clickHandlerRef.current = handler }),
    off: vi.fn()
  }))
  return { initMock, setOptionMock, clickHandlerRef }
})

vi.mock('echarts/core', () => ({
  init: initMock,
  use: vi.fn()
}))
vi.mock('echarts/charts', () => ({ GraphChart: {} }))
vi.mock('echarts/components', () => ({ TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

vi.mock('@/api/knowledge', () => ({
  getGlobalKnowledgeGraph: vi.fn()
}))

import { getGlobalKnowledgeGraph } from '@/api/knowledge'

function mockGraph() {
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
}

describe('KnowledgeGraphView.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    clickHandlerRef.current = null
    mockGraph()
  })

  it('挂载时加载全局图谱', async () => {
    const wrapper = mount(KnowledgeGraphView, {
      attachTo: document.body
    })
    await flushPromises()

    expect(getGlobalKnowledgeGraph).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('数据到达后渲染节点和边', async () => {
    const wrapper = mount(KnowledgeGraphView, {
      attachTo: document.body
    })
    await flushPromises()

    expect(initMock).toHaveBeenCalled()
    const options = setOptionMock.mock.calls[0][0]
    expect(options.series[0].data).toHaveLength(2)
    expect(options.series[0].links).toHaveLength(1)
    expect(options.series[0].links[0].label.formatter).toBe('related_to')
    wrapper.unmount()
  })

  it('空图谱显示空态提示', async () => {
    vi.mocked(getGlobalKnowledgeGraph).mockResolvedValue({
      code: 200, message: 'ok', suggestion: null,
      data: { nodes: [], edges: [] }
    })

    const wrapper = mount(KnowledgeGraphView)
    await flushPromises()

    expect(wrapper.find('.graph-hint').text()).toContain('暂无')
    wrapper.unmount()
  })

  it('点击条目节点 emit select-item', async () => {
    const wrapper = mount(KnowledgeGraphView, {
      attachTo: document.body
    })
    await flushPromises()

    clickHandlerRef.current!({ data: { id: 'item:2' } })

    expect(wrapper.emitted('select-item')).toBeTruthy()
    expect(wrapper.emitted('select-item')![0][0]).toBe(2)
    wrapper.unmount()
  })

  it('点击非条目节点不 emit', async () => {
    const wrapper = mount(KnowledgeGraphView, {
      attachTo: document.body
    })
    await flushPromises()

    clickHandlerRef.current!({ data: { id: 'ext:JVM' } })

    expect(wrapper.emitted('select-item')).toBeFalsy()
    wrapper.unmount()
  })
})
