import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import 'virtual:uno.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import './styles/tokens.css';
import './styles/markdown.css';
import './styles/hljs-dark.css';

createApp(App).use(createPinia()).use(router).mount('#app');
