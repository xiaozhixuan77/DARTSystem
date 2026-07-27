import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatInput from '../ChatInput.vue'

describe('ChatInput.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('渲染输入框和发送按钮', () => {
    const wrapper = mount(ChatInput)
    expect(wrapper.find('textarea').exists()).toBe(true)
    expect(wrapper.find('.send-btn').exists()).toBe(true)
  })

  it('Enter 键触发发送事件', async () => {
    const wrapper = mount(ChatInput)
    const textarea = wrapper.find('textarea')

    await textarea.setValue('Hello DART')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: false })

    expect(wrapper.emitted('send')).toBeTruthy()
    expect(wrapper.emitted('send')![0]).toEqual(['Hello DART'])
  })

  it('发送后清空输入框', async () => {
    const wrapper = mount(ChatInput)
    const textarea = wrapper.find('textarea')

    await textarea.setValue('Test')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: false })

    const emitted = wrapper.emitted('send') as unknown[][]
    expect(emitted[0]).toEqual(['Test'])
    expect((textarea.element as HTMLTextAreaElement).value).toBe('')
  })

  it('Shift+Enter 不发送', async () => {
    const wrapper = mount(ChatInput)
    const textarea = wrapper.find('textarea')

    await textarea.setValue('line1')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })

    expect(wrapper.emitted('send')).toBeFalsy()
  })

  it('空输入不发送', async () => {
    const wrapper = mount(ChatInput)
    const textarea = wrapper.find('textarea')

    await textarea.setValue('   ')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: false })

    expect(wrapper.emitted('send')).toBeFalsy()
  })

  it('空输入时发送按钮禁用', () => {
    const wrapper = mount(ChatInput)
    const btn = wrapper.find('.send-btn')
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('有输入内容时发送按钮可用', async () => {
    const wrapper = mount(ChatInput)
    await wrapper.find('textarea').setValue('test')
    const btn = wrapper.find('.send-btn')
    expect(btn.attributes('disabled')).toBeUndefined()
  })

  it('显示提示文字', () => {
    const wrapper = mount(ChatInput)
    expect(wrapper.find('.input-hint').text()).toContain('Enter')
  })
})
