import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AgentMessage from '../AgentMessage.vue'

describe('AgentMessage.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const mockReport = {
    title: 'Go 并发模式最佳实践',
    content: '# Go 并发\n\n## 核心概念\n- Goroutine\n- Channel',
    sources: [
      { title: 'Go 官方文档', url: 'https://go.dev/doc/concurrency' },
      { title: 'Go by Example', url: 'https://gobyexample.com/goroutines' }
    ],
    scores: { T: 4, A: 5, D: 3, M: 4 },
    weightedScore: 4.1,
    generatedAt: '2026-07-27'
  }

  it('渲染报告标题', () => {
    const wrapper = mount(AgentMessage, { props: { report: mockReport } })
    expect(wrapper.find('.agent-title').text()).toContain('Go 并发模式最佳实践')
  })

  it('渲染 Markdown 内容', () => {
    const wrapper = mount(AgentMessage, { props: { report: mockReport } })
    const content = wrapper.find('.agent-content')
    expect(content.html()).toContain('Goroutine')
    expect(content.html()).toContain('Channel')
  })

  it('渲染来源列表', () => {
    const wrapper = mount(AgentMessage, { props: { report: mockReport } })
    const sources = wrapper.findAll('.source-item')
    expect(sources).toHaveLength(2)
    expect(sources[0].text()).toContain('Go 官方文档')
  })

  it('渲染评分徽章', () => {
    const wrapper = mount(AgentMessage, { props: { report: mockReport } })
    const badges = wrapper.findAll('.score-badge')
    expect(badges).toHaveLength(4)
    expect(badges[0].text()).toContain('T')
    expect(badges[0].text()).toContain('4')
  })

  it('显示综合评分', () => {
    const wrapper = mount(AgentMessage, { props: { report: mockReport } })
    expect(wrapper.find('.agent-meta').text()).toContain('4.1')
  })

  it('无 sources 时不渲染来源区', () => {
    const noSources = { ...mockReport, sources: [] }
    const wrapper = mount(AgentMessage, { props: { report: noSources } })
    expect(wrapper.find('.agent-sources').exists()).toBe(false)
  })

  it('显示生成时间', () => {
    const wrapper = mount(AgentMessage, { props: { report: mockReport } })
    expect(wrapper.find('.agent-meta').text()).toContain('2026-07-27')
  })

  it('空内容显示占位提示', () => {
    const emptyReport = { ...mockReport, content: '', title: '' }
    const wrapper = mount(AgentMessage, { props: { report: emptyReport } })
    // 应该正常渲染不崩溃
    expect(wrapper.find('.agent-message').exists()).toBe(true)
  })
})
