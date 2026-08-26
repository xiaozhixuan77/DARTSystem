import client from './client'
import type { ApiResponse } from '@/types/api'
import type { ConversationVO, MessageVO } from '@/types/chat'

export async function listConversations(): Promise<ApiResponse<ConversationVO[]>> {
  const { data } = await client.get<ApiResponse<ConversationVO[]>>('/conversations')
  return data
}

export async function createConversation(): Promise<ApiResponse<ConversationVO>> {
  const { data } = await client.post<ApiResponse<ConversationVO>>('/conversations', {})
  return data
}

export async function deleteConversation(id: number): Promise<ApiResponse<void>> {
  const { data } = await client.delete<ApiResponse<void>>(`/conversations/${id}`)
  return data
}

export async function getMessages(conversationId: number): Promise<ApiResponse<MessageVO[]>> {
  const { data } = await client.get<ApiResponse<MessageVO[]>>(`/conversations/${conversationId}/messages`)
  return data
}
