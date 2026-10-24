import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import './styles/index.css'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)

useAuthStore().restoreAuth()

app.use(router)
app.use(naive)

app.mount('#app')
