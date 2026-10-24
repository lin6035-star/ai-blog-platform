<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { statsApi, type ActivityStats } from '@/api/stats'

/**
 * 站点活跃小卡片（第三刀 · Bitmap 位图统计）。
 *
 * 独立组件而不是塞进 ProfileView：数据拉取、失败处理、样式都自包含，
 * 宿主页面只需要放一个标签。以后想挪到首页或侧边栏也不用改宿主逻辑。
 */
const stats = ref<ActivityStats | null>(null)

/** 留存率可能是 null（昨日无人活跃）——"算不出来"要显示成 —，不能显示成 0% */
function formatRate(rate: number | null | undefined) {
  if (rate === null || rate === undefined) {
    return '—'
  }
  return `${Math.round(rate * 100)}%`
}

onMounted(async () => {
  try {
    const res = await statsApi.getActivity()
    stats.value = res.data
  } catch {
    // 站点统计是锦上添花：拿不到就整块不渲染，不影响个人中心其余内容
  }
})
</script>

<template>
  <div v-if="stats" class="site-activity">
    <!-- 「全站去重人数」这几个字是必须的：这组数字全是**站点级**口径，
         但它挂在头像弹层里，很容易被读成"我的活跃情况"（真实误读过：
         新注册的账号看到「近 7 天 1」，以为是说自己有 7 天记录）。 -->
    <div class="site-activity-title">
      站点活跃
      <span class="site-activity-scope">全站去重人数</span>
    </div>
    <div class="site-activity-row">
      <span>今日活跃</span>
      <strong>{{ stats.todayActive }} 人</strong>
    </div>
    <div class="site-activity-row">
      <span>近 7 天</span>
      <strong>{{ stats.weekActive }} 人</strong>
    </div>
    <div class="site-activity-row">
      <span>昨日留存</span>
      <strong>{{ formatRate(stats.yesterdayRetentionRate) }}</strong>
    </div>
  </div>
</template>

<style scoped>
.site-activity {
  display: grid;
  gap: 8px;
  padding: 14px 20px;
  border-top: 1px solid #eef0f2;
}

.site-activity-title {
  color: #94a3b8;
  font-size: 12px;
}

.site-activity-scope {
  margin-left: 6px;
  color: #cbd5e1;
  font-size: 11px;
}

.site-activity-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  color: #4b5563;
  font-size: 13px;
}

.site-activity-row strong {
  color: #1f2937;
  font-size: 14px;
  font-weight: 600;
}
</style>
