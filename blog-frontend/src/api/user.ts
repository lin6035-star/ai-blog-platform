import request from '@/utils/request'
import type { UserProfileDTO, UsersVO } from '@/types/user'

export const userApi = {
  getMe() {
    return request.get<UsersVO>('/users/me')
  },
  updateProfile(payload: UserProfileDTO) {
    return request.put<UsersVO>('/users/me/profile', payload)
  },
  uploadAvatar(file: File) {
    const formData = new FormData()
    formData.append('file', file)

    return request.post<UsersVO>('/users/me/avatar', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    })
  },
}
