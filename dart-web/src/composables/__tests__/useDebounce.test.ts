import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ref, nextTick } from 'vue'
import { useDebounce } from '../useDebounce'

describe('useDebounce', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('初始值等于 source 当前值', () => {
    const source = ref('hello')
    const debounced = useDebounce(() => source.value, 300)
    // immediate: true 会立即设置初始值
    expect(debounced.value).toBe('hello')
  })

  it('快速变更后仅最后一次生效', async () => {
    const source = ref('a')

    const debounced = useDebounce(() => source.value, 300)

    source.value = 'b'
    await nextTick()
    source.value = 'c'
    await nextTick()
    source.value = 'd'
    await nextTick()

    // 推进 300ms
    vi.advanceTimersByTime(300)
    await nextTick()

    expect(debounced.value).toBe('d')
  })

  it('延迟期间未到不更新', async () => {
    const source = ref('old')

    const debounced = useDebounce(() => source.value, 500)

    source.value = 'new'
    await nextTick()

    // 仅推进 200ms，未到 500ms
    vi.advanceTimersByTime(200)
    await nextTick()

    expect(debounced.value).toBe('old')
  })

  it('延迟到后更新值', async () => {
    const source = ref('old')

    const debounced = useDebounce(() => source.value, 500)

    source.value = 'new'
    await nextTick()

    vi.advanceTimersByTime(500)
    await nextTick()

    expect(debounced.value).toBe('new')
  })

  it('中间再次变更重置延迟', async () => {
    const source = ref(0)

    const debounced = useDebounce(() => source.value, 300)

    source.value = 1
    await nextTick()
    vi.advanceTimersByTime(200)

    // 在 200ms 时再次变更，应该重置 timer
    source.value = 2
    await nextTick()
    vi.advanceTimersByTime(200)

    // 总共只过了 400ms 从第一次变更算起，但第二次变更在 200ms 重置了
    // 所以从第二次变更过了 200ms，还没到 300ms
    expect(debounced.value).toBe(0)

    vi.advanceTimersByTime(100)
    await nextTick()
    expect(debounced.value).toBe(2)
  })
})
