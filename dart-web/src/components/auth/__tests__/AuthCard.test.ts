import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AuthCard from '../AuthCard.vue'

describe('AuthCard.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('默认显示登录表单', () => {
    const wrapper = mount(AuthCard, { props: { activeTab: 'login' } })
    // v-show 隐藏的也是存在的，所以两个 form 都在
    const forms = wrapper.findAll('form')
    expect(forms).toHaveLength(2)
  })

  it('切换 Tab 触发 switchTab 事件', async () => {
    const wrapper = mount(AuthCard, { props: { activeTab: 'login' } })
    const tabs = wrapper.findAll('.auth-tab')

    await tabs[1].trigger('click')
    expect(wrapper.emitted('switchTab')).toBeTruthy()
    expect(wrapper.emitted('switchTab')![0]).toEqual(['register'])
  })

  it('登录 Tab 高亮', () => {
    const wrapper = mount(AuthCard, { props: { activeTab: 'login' } })
    const tabs = wrapper.findAll('.auth-tab')
    expect(tabs[0].classes()).toContain('active')
    expect(tabs[1].classes()).not.toContain('active')
  })

  it('注册 Tab 高亮', () => {
    const wrapper = mount(AuthCard, { props: { activeTab: 'register' } })
    const tabs = wrapper.findAll('.auth-tab')
    expect(tabs[1].classes()).toContain('active')
  })

  it('提交登录表单触发 submit 事件', async () => {
    const wrapper = mount(AuthCard, { props: { activeTab: 'login' } })

    await wrapper.find('#loginUser').setValue('testuser')
    await wrapper.find('#loginPass').setValue('secret123')
    await wrapper.find('form').trigger('submit.prevent')

    expect(wrapper.emitted('submit')).toBeTruthy()
    expect(wrapper.emitted('submit')![0]).toEqual([{
      username: 'testuser',
      password: 'secret123'
    }])
  })

  it('提交注册表单包含 confirmPassword', async () => {
    const wrapper = mount(AuthCard, { props: { activeTab: 'register' } })

    const forms = wrapper.findAll('form')
    // 注册表单是第二个
    await forms[1].find('#regUser').setValue('newuser')
    await forms[1].find('#regPass').setValue('pass123')
    await forms[1].find('#regPass2').setValue('pass123')
    await forms[1].trigger('submit.prevent')

    expect(wrapper.emitted('submit')).toBeTruthy()
    expect(wrapper.emitted('submit')![0]).toEqual([{
      username: 'newuser',
      password: 'pass123',
      confirmPassword: 'pass123'
    }])
  })

  it('底部有去注册/去登录链接', () => {
    const wrapper = mount(AuthCard, { props: { activeTab: 'login' } })
    const links = wrapper.findAll('.form-footer a')
    expect(links[0].text()).toBe('去注册')
  })
})
