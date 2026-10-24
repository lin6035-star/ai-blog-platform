/**
 * 眼动追踪 composable
 * 从 animated-login 迁移：requestAnimationFrame 循环 + CSS 变量输出
 */
import { onMounted, onUnmounted } from 'vue'

const mouseNorm = { x: 0, y: 0 }
let currentX = 0
let currentY = 0
let rafId = 0

function onMouseMove(e: MouseEvent) {
  mouseNorm.x = (e.clientX / window.innerWidth - 0.5) * 2
  mouseNorm.y = (e.clientY / window.innerHeight - 0.5) * 2
}

function animate() {
  currentX += (mouseNorm.x - currentX) * 0.08
  currentY += (mouseNorm.y - currentY) * 0.08

  document.documentElement.style.setProperty('--mx', String(currentX))
  document.documentElement.style.setProperty('--my', String(currentY))

  rafId = requestAnimationFrame(animate)
}

export function useEyeTracking() {
  onMounted(() => {
    document.addEventListener('mousemove', onMouseMove)
    rafId = requestAnimationFrame(animate)
  })

  onUnmounted(() => {
    document.removeEventListener('mousemove', onMouseMove)
    cancelAnimationFrame(rafId)
  })

  return { mouseNorm }
}
