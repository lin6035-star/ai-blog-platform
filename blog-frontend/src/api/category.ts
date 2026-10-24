import request from '@/utils/request'
import type { Category } from '@/types/category'

export const categoryApi = {
  getList() {
    return request.get<Category[]>('/categories')
  },
}
