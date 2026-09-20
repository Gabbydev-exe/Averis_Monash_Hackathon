import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import InboxView from '../views/InboxView.vue'

const routes = [
  {
    path: '/',
    name: 'home',
    component: HomeView,
  },
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
