import { ref, watch } from 'vue'

export function useDebounce<T>(source: () => T, delay = 300) {
  const debounced = ref(source())

  let timer: ReturnType<typeof setTimeout>
  watch(
    source,
    (val) => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        debounced.value = val
      }, delay)
    },
    { immediate: true }
  )

  return debounced
}
