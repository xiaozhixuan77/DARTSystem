import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import WelcomeScreen from '../WelcomeScreen.vue'

describe('WelcomeScreen.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('显示 DART 标题', () => {
    const wrapper = mount(WelcomeScreen)
    expect(wrapper.find('.welcome-title').text()).toBe('DART 智能技术检索')
  })

  it('显示三步引导', () => {
    const wrapper = mount(WelcomeScreen)
    const steps = wrapper.findAll('.step')
    expect(steps).toHaveLength(3)
    expect(steps[0].text()).toContain('输入')
    expect(steps[1].text()).toContain('搜索')
    expect(steps[2].text()).toContain('报告')
  })

  it('显示描述文字', () => {
    const wrapper = mount(WelcomeScreen)
    expect(wrapper.find('.welcome-desc').text()).toContain('Search Agent')
  })

  it('显示 D 图标', () => {
    const wrapper = mount(WelcomeScreen)
    expect(wrapper.find('.welcome-icon').text()).toBe('D')
  })
})
