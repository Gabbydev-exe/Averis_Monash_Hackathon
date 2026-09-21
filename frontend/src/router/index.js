import { createRouter, createWebHistory } from 'vue-router'
import InboxView from '../views/InboxView.vue'
import DataView from '../views/DataView.vue'

const routes = [
  {
    path: '/',
    redirect: '/inbox',
  },
  { path: '/data', name: 'data', component: DataView },
  {
    path: '/inbox',
    name: 'inbox',
    component: InboxView,
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
