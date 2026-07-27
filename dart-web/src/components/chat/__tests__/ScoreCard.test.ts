import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ScoreCard from '../ScoreCard.vue'

describe('ScoreCard.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const defaultScores = { T: 4, A: 5, D: 3, M: 4 }

  it('渲染四个维度标签', () => {
    const wrapper = mount(ScoreCard, { props: { scores: defaultScores } })
    const items = wrapper.findAll('.score-item')
    expect(items).toHaveLength(4)

    const labels = wrapper.findAll('.dim-label')
    expect(labels[0].text()).toBe('时效性')
    expect(labels[1].text()).toBe('权威性')
    expect(labels[2].text()).toBe('深度')
    expect(labels[3].text()).toBe('匹配度')
  })

  it('显示各维度分值', () => {
    const wrapper = mount(ScoreCard, { props: { scores: defaultScores } })
    const values = wrapper.findAll('.dim-value')
    expect(values[0].text()).toBe('4.0')
    expect(values[1].text()).toBe('5.0')
    expect(values[2].text()).toBe('3.0')
    expect(values[3].text()).toBe('4.0')
  })

  it('条形图宽度与分值成正比', () => {
    const wrapper = mount(ScoreCard, { props: { scores: defaultScores } })
    const bars = wrapper.findAll('.bar-fill')
    // A=5 的宽度应为 100%, M=4 为 80%, T=4 为 80%, D=3 为 60%
    expect(bars[1].attributes('style')).toContain('width: 100%')
    expect(bars[3].attributes('style')).toContain('width: 80%')
    expect(bars[2].attributes('style')).toContain('width: 60%')
  })

  it('传入加权总分时显示', () => {
    const wrapper = mount(ScoreCard, {
      props: { scores: defaultScores, weighted: 4.25 }
    })
    expect(wrapper.find('.weighted-score').exists()).toBe(true)
    expect(wrapper.find('.weighted-score').text()).toContain('4.25')
  })

  it('不传加权总分时不显示', () => {
    const wrapper = mount(ScoreCard, { props: { scores: defaultScores } })
    expect(wrapper.find('.weighted-score').exists()).toBe(false)
  })
})
