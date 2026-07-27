import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import KnowledgeCard from '../KnowledgeCard.vue'

describe('KnowledgeCard.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const mockItem = {
    id: 1,
    title: 'Go 并发模式详解',
    sourceUrl: 'https://go.dev/tour/concurrency',
    tags: 'Go',
    scoreT: 4,
    scoreA: 5,
    scoreD: 3,
    scoreM: 4,
    weightedScore: 4.1,
    createdAt: '2026-01-01'
  }

  it('渲染标题和来源', () => {
    const wrapper = mount(KnowledgeCard, { props: { item: mockItem } })
    expect(wrapper.find('.card-title').text()).toBe('Go 并发模式详解')
    expect(wrapper.find('.card-source').text()).toBe('https://go.dev/tour/concurrency')
  })

  it('渲染四维评分', () => {
    const wrapper = mount(KnowledgeCard, { props: { item: mockItem } })
    const scores = wrapper.findAll('.score-row')
    expect(scores).toHaveLength(4)
    expect(scores[0].text()).toContain('4')
    expect(scores[1].text()).toContain('5')
  })

  it('显示综合评分', () => {
    const wrapper = mount(KnowledgeCard, { props: { item: mockItem } })
    expect(wrapper.find('.score-total').text()).toContain('4.1')
  })

  it('无加权评分时不显示综合评分', () => {
    const noScore = { ...mockItem, weightedScore: undefined }
    const wrapper = mount(KnowledgeCard, { props: { item: noScore } })
    expect(wrapper.find('.score-total').exists()).toBe(false)
  })

  it('点击触发 select 事件', async () => {
    const wrapper = mount(KnowledgeCard, { props: { item: mockItem } })
    await wrapper.trigger('click')
    expect(wrapper.emitted('select')).toBeTruthy()
    expect(wrapper.emitted('select')![0]).toEqual([1])
  })

  it('无 sourceUrl 时不渲染来源', () => {
    const noUrl = { ...mockItem, sourceUrl: undefined }
    const wrapper = mount(KnowledgeCard, { props: { item: noUrl } })
    expect(wrapper.find('.card-source').exists()).toBe(false)
  })
})
