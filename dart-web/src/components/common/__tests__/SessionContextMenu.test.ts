import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SessionContextMenu from '../SessionContextMenu.vue'

describe('SessionContextMenu.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('不显示时不渲染', () => {
    const wrapper = mount(SessionContextMenu, {
      props: { visible: false, x: 100, y: 200, conversationTitle: 'Test' }
    })
    expect(wrapper.find('.context-menu').exists()).toBe(false)
  })

  it('显示时渲染菜单', () => {
    const wrapper = mount(SessionContextMenu, {
      props: { visible: true, x: 100, y: 200, conversationTitle: 'Go 并发' }
    })
    expect(wrapper.find('.context-menu').exists()).toBe(true)
  })

  it('正确定位菜单位置', () => {
    const wrapper = mount(SessionContextMenu, {
      props: { visible: true, x: 150, y: 300, conversationTitle: 'Test' }
    })
    const menu = wrapper.find('.context-menu')
    expect(menu.attributes('style')).toContain('left: 150px')
    expect(menu.attributes('style')).toContain('top: 300px')
  })

  it('包含删除选项', () => {
    const wrapper = mount(SessionContextMenu, {
      props: { visible: true, x: 0, y: 0, conversationTitle: 'Test' }
    })
    const items = wrapper.findAll('.menu-item')
    expect(items).toHaveLength(1)
    expect(items[0].text()).toContain('删除')
  })

  it('点击删除触发 delete 事件', async () => {
    const wrapper = mount(SessionContextMenu, {
      props: { visible: true, x: 0, y: 0, conversationTitle: 'Test' }
    })
    await wrapper.findAll('.menu-item')[0].trigger('click')
    expect(wrapper.emitted('delete')).toBeTruthy()
  })

  it('删除项有危险样式', () => {
    const wrapper = mount(SessionContextMenu, {
      props: { visible: true, x: 0, y: 0, conversationTitle: 'Test' }
    })
    const deleteItem = wrapper.findAll('.menu-item')[0]
    expect(deleteItem.classes()).toContain('danger')
  })
})
