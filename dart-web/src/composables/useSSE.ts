import { ref, onUnmounted } from 'vue'

export interface SSEOptions {
  headers?: Record<string, string>
  signal?: AbortSignal
}

export type SSEStatus = 'idle' | 'connecting' | 'connected' | 'error'

export function useSSE() {
  const status = ref<SSEStatus>('idle')
  const error = ref<string | null>(null)
  let controller: AbortController | null = null

  async function connect(
    url: string,
    body: unknown,
    callbacks: {
      onEvent?: (event: string, data: string) => void
      onError?: (err: string) => void
      onComplete?: () => void
    }
  ) {
    controller = new AbortController()
    status.value = 'connecting'
    error.value = null

    try {
      const token = localStorage.getItem('access_token')
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      }

      const response = await fetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal
      })

      if (!response.ok) {
        status.value = 'error'
        error.value = `HTTP ${response.status}`
        callbacks.onError?.(`HTTP ${response.status}`)
        return
      }

      status.value = 'connected'
      const reader = response.body?.getReader()
      if (!reader) return

      const decoder = new TextDecoder()
      let buffer = ''
      let currentEvent = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            callbacks.onEvent?.(currentEvent || 'message', data)
            currentEvent = ''
          }
        }
      }

      status.value = 'idle'
      callbacks.onComplete?.()
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        status.value = 'idle'
        return
      }
      status.value = 'error'
      const msg = err instanceof Error ? err.message : 'Unknown error'
      error.value = msg
      callbacks.onError?.(msg)
    }
  }

  function disconnect() {
    controller?.abort()
    controller = null
    status.value = 'idle'
  }

  onUnmounted(() => {
    disconnect()
  })

  return { status, error, connect, disconnect }
}
