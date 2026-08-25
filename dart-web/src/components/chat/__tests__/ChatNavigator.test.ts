import { describe, it, expect, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import ChatNavigator from '../ChatNavigator.vue'
import type { MessageVO } from '@/types/chat'

function makeMessages(): MessageVO[] {
  const now = new Date().toISOString()
  return [
    { id: 1, role: 'user', content: '什么是 Java 虚拟线程？', createdAt: now },
    { id: 2, role: 'assistant', content: '虚拟线程是 JDK 21 引入的轻量级线程…', createdAt: now },
    { id: 3, role: 'user', content: '和平台线程有什么区别？', createdAt: now },
    { id: 4, role: 'assistant', content: '主要区别在于调度与栈占用…', createdAt: now },
  ]
}

describe('ChatNavigator.vue', () => {
  let wrapper: VueWrapper | null = null

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('无用户消息时不渲染根容器', () => {
    wrapper = mount(ChatNavigator, {
      props: { messages: [makeMessages()[1]], activeIndex: -1 },
    })
    expect(wrapper.find('.chat-navigator').exists()).toBe(false)
  })

  it('有用户消息时渲染半圆按钮，点击展开问题列表', async () => {
    wrapper = mount(ChatNavigator, {
      props: { messages: makeMessages(), activeIndex: -1 },
    })
    expect(wrapper.find('.nav-toggle').exists()).toBe(true)
    expect(wrapper.find('.nav-panel').exists()).toBe(false)

    await wrapper.find('.nav-toggle').trigger('click')
    expect(wrapper.find('.nav-panel').exists()).toBe(true)
    const items = wrapper.findAll('.nav-item')
    expect(items).toHaveLength(2)
    expect(items[0].text()).toContain('什么是 Java 虚拟线程？')
    expect(items[1].text()).toContain('和平台线程有什么区别？')
  })

  it('点击导航项 emit navigate(消息数组索引) 并收起', async () => {
    wrapper = mount(ChatNavigator, {
      props: { messages: makeMessages(), activeIndex: -1 },
    })
    await wrapper.find('.nav-toggle').trigger('click')
    await wrapper.findAll('.nav-item')[1].trigger('click')
    // 第二条用户消息位于消息数组索引 2（user/assistant 交替）
    expect(wrapper.emitted('navigate')![0]).toEqual([2])
    expect(wrapper.find('.nav-panel').exists()).toBe(false)
  })

  it('再次点击按钮收起抽屉', async () => {
    wrapper = mount(ChatNavigator, {
      props: { messages: makeMessages(), activeIndex: -1 },
    })
    const toggle = wrapper.find('.nav-toggle')
    await toggle.trigger('click')
    expect(wrapper.find('.nav-panel').exists()).toBe(true)
    await toggle.trigger('click')
    expect(wrapper.find('.nav-panel').exists()).toBe(false)
  })

  it('展开时点击页面其他区域收起', async () => {
    wrapper = mount(ChatNavigator, {
      props: { messages: makeMessages(), activeIndex: -1 },
      attachTo: document.body,
    })
    await wrapper.find('.nav-toggle').trigger('click')
    expect(wrapper.find('.nav-panel').exists()).toBe(true)

    document.body.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.nav-panel').exists()).toBe(false)
  })

  it('点击抽屉内部不收起', async () => {
    wrapper = mount(ChatNavigator, {
      props: { messages: makeMessages(), activeIndex: -1 },
      attachTo: document.body,
    })
    await wrapper.find('.nav-toggle').trigger('click')

    await wrapper.find('.nav-panel').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.nav-panel').exists()).toBe(true)
  })

  it('activeIndex 对应项高亮', async () => {
    wrapper = mount(ChatNavigator, {
      props: { messages: makeMessages(), activeIndex: 2 },
    })
    await wrapper.find('.nav-toggle').trigger('click')
    const items = wrapper.findAll('.nav-item')
    expect(items[1].classes()).toContain('active')
    expect(items[0].classes()).not.toContain('active')
  })
})
