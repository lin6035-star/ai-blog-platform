import request from '@/utils/request'
import type { Tag } from '@/types/tag'

export const tagApi = {
  getList() {
    return request.get<Tag[]>('/tags')
  },
}
