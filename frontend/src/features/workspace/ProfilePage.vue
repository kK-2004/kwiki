<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../auth/store';

const auth = useAuthStore();
const router = useRouter();
const userName = computed(() => auth.user?.username || 'kk');
const profileForm = reactive({ nickname: '', email: '' });
const savedProfile = ref({ nickname: '', email: '' });
const profileInitialized = ref(false);
const avatarUrl = ref<string | null>(null);

const avatarModalOpen = ref(false);
const passwordModalOpen = ref(false);
const draftAvatarUrl = ref<string | null>(null);
const draftObjectUrl = ref<string | null>(null);
const avatarObjectUrl = ref<string | null>(null);
const draftZoom = ref(100);
const draftOffset = reactive({ x: 0, y: 0 });
const dragging = ref(false);
const dragOrigin = reactive({ x: 0, y: 0, offsetX: 0, offsetY: 0 });
const fileInput = ref<HTMLInputElement | null>(null);

const passwordForm = reactive({ current: '', next: '', confirm: '' });
const visiblePasswords = reactive({ current: false, next: false, confirm: false });
const toastText = ref('');
const toastVisible = ref(false);
let toastTimer: number | undefined;

const initial = computed(() => (profileForm.nickname.trim() || userName.value).slice(0, 1).toUpperCase());
const passwordStrength = computed(() => {
  const value = passwordForm.next;
  let score = 0;
  if (value.length >= 8) score += 1;
  if (/[a-zA-Z]/.test(value) && /\d/.test(value)) score += 1;
  if (/[^a-zA-Z0-9]/.test(value)) score += 1;
  if (value.length >= 12) score += 1;
  return score;
});
const avatarStyle = computed(() => avatarUrl.value ? {
  backgroundImage: `url("${avatarUrl.value}")`, backgroundSize: 'cover', backgroundPosition: 'center',
} : {});
const draftImageStyle = computed(() => ({
  backgroundImage: draftAvatarUrl.value ? `url("${draftAvatarUrl.value}")` : 'none',
  transform: `translate(${draftOffset.x}px, ${draftOffset.y}px) scale(${draftZoom.value / 100})`,
}));
const previewImageStyle = computed(() => ({
  backgroundImage: draftAvatarUrl.value ? `url("${draftAvatarUrl.value}")` : 'none',
  transform: `translate(${draftOffset.x / 2}px, ${draftOffset.y / 2}px) scale(${draftZoom.value / 100})`,
}));

function applyProfile(user = auth.user) {
  let stored: Partial<typeof profileForm> = {};
  try { stored = JSON.parse(localStorage.getItem('kwiki.profile') || '{}'); } catch { /* 使用账号默认值 */ }
  const next = {
    nickname: String(stored.nickname || user?.displayName || user?.username || 'kk'),
    email: String(stored.email || user?.email || ''),
  };
  savedProfile.value = next;
  Object.assign(profileForm, next);
  profileInitialized.value = true;
}

watch(() => auth.user, (user) => {
  if (user && !profileInitialized.value) applyProfile(user);
}, { immediate: true });
onMounted(() => { if (!profileInitialized.value) applyProfile(); });

function showToast(message: string) {
  toastText.value = message;
  toastVisible.value = true;
  if (toastTimer) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => { toastVisible.value = false; }, 1800);
}

function resetProfile() {
  Object.assign(profileForm, savedProfile.value);
  showToast('已重置为上次保存内容');
}

function saveProfile() {
  const nickname = profileForm.nickname.trim();
  if (!nickname) { showToast('请输入昵称'); return; }
  savedProfile.value = { nickname, email: profileForm.email.trim() };
  Object.assign(profileForm, savedProfile.value);
  localStorage.setItem('kwiki.profile', JSON.stringify(savedProfile.value));
  showToast('个人资料已保存');
}

function openAvatarEditor() {
  draftAvatarUrl.value = avatarUrl.value;
  draftZoom.value = avatarUrl.value ? 108 : 100;
  draftOffset.x = 0; draftOffset.y = 0;
  avatarModalOpen.value = true;
}

function releaseDraftObjectUrl() {
  if (draftObjectUrl.value) {
    URL.revokeObjectURL(draftObjectUrl.value);
    draftObjectUrl.value = null;
  }
}

function closeAvatarEditor() {
  releaseDraftObjectUrl();
  draftAvatarUrl.value = avatarUrl.value;
  avatarModalOpen.value = false;
}

function handleAvatarFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!file.type.startsWith('image/')) { showToast('请选择图片文件'); input.value = ''; return; }
  if (file.size > 5 * 1024 * 1024) { showToast('图片不能超过 5MB'); input.value = ''; return; }
  releaseDraftObjectUrl();
  draftObjectUrl.value = URL.createObjectURL(file);
  draftAvatarUrl.value = draftObjectUrl.value;
  draftZoom.value = 108; draftOffset.x = 0; draftOffset.y = 0;
  showToast('图片已载入，可继续裁剪');
}

function resetAvatar() {
  releaseDraftObjectUrl();
  draftAvatarUrl.value = null; draftZoom.value = 100; draftOffset.x = 0; draftOffset.y = 0;
  if (fileInput.value) fileInput.value.value = '';
}

function saveAvatar() {
  const nextAvatarUrl = draftAvatarUrl.value;
  if (avatarObjectUrl.value && avatarObjectUrl.value !== nextAvatarUrl) URL.revokeObjectURL(avatarObjectUrl.value);
  avatarUrl.value = nextAvatarUrl;
  avatarObjectUrl.value = draftObjectUrl.value;
  draftObjectUrl.value = null;
  avatarModalOpen.value = false;
  showToast('头像已保存');
}

function startCropDrag(event: PointerEvent) {
  if (!draftAvatarUrl.value) return;
  dragging.value = true;
  dragOrigin.x = event.clientX; dragOrigin.y = event.clientY;
  dragOrigin.offsetX = draftOffset.x; dragOrigin.offsetY = draftOffset.y;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}

function moveCropDrag(event: PointerEvent) {
  if (!dragging.value) return;
  draftOffset.x = dragOrigin.offsetX + event.clientX - dragOrigin.x;
  draftOffset.y = dragOrigin.offsetY + event.clientY - dragOrigin.y;
}

function stopCropDrag(event?: PointerEvent) {
  if (event && (event.currentTarget as HTMLElement).hasPointerCapture(event.pointerId)) {
    (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId);
  }
  dragging.value = false;
}

function togglePassword(field: keyof typeof visiblePasswords) { visiblePasswords[field] = !visiblePasswords[field]; }

function closePasswordEditor() {
  passwordModalOpen.value = false;
  passwordForm.current = ''; passwordForm.next = ''; passwordForm.confirm = '';
  visiblePasswords.current = false; visiblePasswords.next = false; visiblePasswords.confirm = false;
}

function savePassword() {
  if (!passwordForm.next) { showToast('请输入新密码'); return; }
  if (passwordForm.next !== passwordForm.confirm) { showToast('两次输入的新密码不一致'); return; }
  closePasswordEditor(); showToast('密码已更新');
}

function logout() {
  auth.logout();
  void router.replace({ name: 'login' });
}

function closeModals() {
  if (avatarModalOpen.value) closeAvatarEditor();
  if (passwordModalOpen.value) closePasswordEditor();
}

onBeforeUnmount(() => {
  releaseDraftObjectUrl();
  if (avatarObjectUrl.value) URL.revokeObjectURL(avatarObjectUrl.value);
  if (toastTimer) window.clearTimeout(toastTimer);
});
</script>

<template>
  <main class="profile-page" @keydown.esc.window="closeModals">
    <div class="profile-content">
      <header class="profile-page-head">
        <div><h1>个人设置</h1><p>管理个人资料、账号安全与登录状态。</p></div>
      </header>

      <div class="profile-settings-grid">
        <section class="profile-card">
          <div class="profile-card-head"><h2>个人资料</h2><p>设置头像、昵称以及可选的联系方式。</p></div>
          <div class="profile-body">
            <div class="profile-side">
              <div class="profile-avatar-wrap"><div class="profile-avatar" :style="avatarStyle">{{ avatarUrl ? '' : initial }}</div><button class="profile-avatar-edit" aria-label="更换头像" @click="openAvatarEditor"><i class="i-lucide-camera" /></button></div>
              <div class="profile-identity"><strong>{{ profileForm.nickname || userName }}</strong><span>@ {{ userName }}</span></div>
            </div>
            <div class="profile-fields">
              <div class="profile-field"><label for="profile-nickname">昵称</label><input id="profile-nickname" v-model="profileForm.nickname" class="profile-input" autocomplete="nickname" /><small>显示在知识库协作、评论与聊天界面中。</small></div>
              <div class="profile-field"><label for="profile-email"><span>邮箱</span><span class="profile-optional">选填</span></label><input id="profile-email" v-model="profileForm.email" class="profile-input" type="email" placeholder="请输入邮箱" autocomplete="email" /><small>当前仅保存邮箱信息，不会自动发送验证邮件。</small></div>
              <div class="profile-actions"><button class="profile-btn" @click="resetProfile">重置</button><button class="profile-btn profile-btn-primary" @click="saveProfile">保存修改</button></div>
            </div>
          </div>
        </section>

        <div class="profile-settings-list">
          <section class="profile-setting-row"><div class="profile-setting-icon"><i class="i-lucide-key-round" /></div><div class="profile-setting-info"><strong>密码与安全</strong><span>定期更新密码，保护账号安全。</span></div><button class="profile-btn" @click="passwordModalOpen = true">修改密码</button></section>
          <section class="profile-setting-row"><div class="profile-setting-icon is-danger"><i class="i-lucide-log-out" /></div><div class="profile-setting-info"><strong>退出登录</strong><span>退出当前设备上的登录状态，不会影响其他设备。</span></div><button class="profile-btn profile-btn-danger" @click="logout">退出登录</button></section>
        </div>
      </div>
    </div>

    <div v-if="avatarModalOpen" class="profile-modal-layer" @click.self="closeAvatarEditor">
      <div class="profile-modal profile-modal-large" role="dialog" aria-modal="true" aria-labelledby="avatar-title">
        <div class="profile-modal-head"><div><h3 id="avatar-title">更换头像</h3><p>拖动或缩放图片，在圆形参考线内调整至满意位置。</p></div><button class="profile-close" aria-label="关闭" @click="closeAvatarEditor"><i class="i-lucide-x" /></button></div>
        <div class="profile-modal-body">
          <div class="avatar-edit-layout">
            <div class="crop-section">
              <div class="crop-stage" :class="{ 'is-draggable': !!draftAvatarUrl, 'is-dragging': dragging }" @pointerdown="startCropDrag" @pointermove="moveCropDrag" @pointerup="stopCropDrag" @pointercancel="stopCropDrag"><div class="crop-image" :style="draftImageStyle" /><div class="crop-mask" /><div v-if="!draftAvatarUrl" class="crop-placeholder">{{ initial }}</div></div>
              <div class="zoom-row"><button class="zoom-btn" aria-label="缩小" @click="draftZoom = Math.max(100, draftZoom - 5)"><i class="i-lucide-minus" /></button><input v-model.number="draftZoom" type="range" min="100" max="180" aria-label="头像缩放" /><button class="zoom-btn" aria-label="放大" @click="draftZoom = Math.min(180, draftZoom + 5)"><i class="i-lucide-plus" /></button></div>
            </div>
            <div class="preview-section"><div class="preview-title">头像预览</div><div class="preview-circle-large"><div class="preview-image" :style="previewImageStyle" /><span v-if="!draftAvatarUrl">{{ initial }}</span></div><div class="upload-tip">支持 JPG / PNG / WebP<br />最大支持 5MB</div><label class="profile-btn upload-btn"><i class="i-lucide-image" />重新选择<input ref="fileInput" type="file" accept="image/*" hidden @change="handleAvatarFile" /></label></div>
          </div>
        </div>
        <div class="profile-modal-foot"><button class="profile-btn" @click="resetAvatar">恢复默认</button><div class="profile-modal-right"><button class="profile-btn" @click="closeAvatarEditor">取消</button><button class="profile-btn profile-btn-primary" @click="saveAvatar">保存头像</button></div></div>
      </div>
    </div>

    <div v-if="passwordModalOpen" class="profile-modal-layer" @click.self="closePasswordEditor">
      <div class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="password-title">
        <div class="profile-modal-head"><div><h3 id="password-title">修改密码</h3><p>为账号设置一个新的登录密码。</p></div><button class="profile-close" aria-label="关闭" @click="closePasswordEditor"><i class="i-lucide-x" /></button></div>
        <div class="profile-modal-body"><div class="password-fields">
          <div class="profile-field"><label for="current-password">当前密码</label><div class="password-input"><input id="current-password" v-model="passwordForm.current" class="profile-input" :type="visiblePasswords.current ? 'text' : 'password'" autocomplete="current-password" /><button class="eye-btn" :aria-label="visiblePasswords.current ? '隐藏密码' : '显示密码'" @click="togglePassword('current')"><i :class="visiblePasswords.current ? 'i-lucide-eye-off' : 'i-lucide-eye'" /></button></div></div>
          <div class="profile-field"><label for="new-password">新密码</label><div class="password-input"><input id="new-password" v-model="passwordForm.next" class="profile-input" :type="visiblePasswords.next ? 'text' : 'password'" placeholder="请输入新密码" autocomplete="new-password" /><button class="eye-btn" :aria-label="visiblePasswords.next ? '隐藏密码' : '显示密码'" @click="togglePassword('next')"><i :class="visiblePasswords.next ? 'i-lucide-eye-off' : 'i-lucide-eye'" /></button></div><div class="strength"><span v-for="index in 4" :key="index" :class="{ on: index <= passwordStrength }" /></div><div class="password-rules">建议 8 位以上，并包含字母、数字或符号中的至少两类。</div></div>
          <div class="profile-field"><label for="confirm-password">确认新密码</label><div class="password-input"><input id="confirm-password" v-model="passwordForm.confirm" class="profile-input" :type="visiblePasswords.confirm ? 'text' : 'password'" placeholder="再次输入新密码" autocomplete="new-password" /><button class="eye-btn" :aria-label="visiblePasswords.confirm ? '隐藏密码' : '显示密码'" @click="togglePassword('confirm')"><i :class="visiblePasswords.confirm ? 'i-lucide-eye-off' : 'i-lucide-eye'" /></button></div></div>
        </div></div>
        <div class="profile-modal-foot"><span /><div class="profile-modal-right"><button class="profile-btn" @click="closePasswordEditor">取消</button><button class="profile-btn profile-btn-primary" @click="savePassword">确认修改</button></div></div>
      </div>
    </div>

    <div class="profile-toast" :class="{ show: toastVisible }" role="status">{{ toastText }}</div>
  </main>
</template>

<style scoped>
.profile-page { min-height: 100%; padding: 40px clamp(24px, 4vw, 60px) 72px; color: #17211b; background: radial-gradient(circle at 16% 0%, rgba(46,112,76,.055), transparent 32%), linear-gradient(180deg,#f8faf9 0,#f5f7f6 100%); }
.profile-content { max-width: 1040px; margin: 0 auto; }
.profile-page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.profile-page-head h1 { margin: 0; font-size: 32px; line-height: 1.14; letter-spacing: -1.1px; font-weight: 780; }.profile-page-head p { margin: 8px 0 0; color: #6f7d74; font-size: 14px; }
.profile-settings-grid { display: grid; gap: 18px; }.profile-card { overflow: hidden; border: 1px solid #e5ebe7; border-radius: 16px; background: rgba(255,255,255,.92); box-shadow: 0 4px 18px rgba(26,51,37,.025); }.profile-card-head { padding: 24px 26px 0; }.profile-card-head h2 { margin: 0; font-size: 18px; letter-spacing: -.3px; }.profile-card-head p { margin: 7px 0 0; color: #6f7d74; font-size: 13px; }
.profile-body { padding: 22px 26px 26px; display: grid; grid-template-columns: 220px minmax(0,1fr); gap: 28px; }.profile-side { padding-right: 26px; border-right: 1px solid #e5ebe7; display: flex; flex-direction: column; align-items: flex-start; }.profile-avatar-wrap { position: relative; margin-bottom: 13px; }.profile-avatar { width: 88px; height: 88px; border: 1px solid #e0e9e3; border-radius: 24px; display: grid; place-items: center; background: linear-gradient(145deg,#edf5f0,#dbeae1); color: #2b6744; font-size: 30px; font-weight: 780; }.profile-avatar-edit { position: absolute; right: -5px; bottom: -5px; width: 34px; height: 34px; border: 3px solid white; border-radius: 10px; display: grid; place-items: center; background: #1f5d3a; color: white; box-shadow: 0 5px 12px rgba(31,93,58,.25); cursor: pointer; }.profile-avatar-edit i,.profile-setting-icon i { font-size: 18px; }.profile-identity strong { display: block; margin-bottom: 3px; font-size: 16px; }.profile-identity span { color: #6f7d74; font-size: 13px; }
.profile-fields { display: grid; gap: 17px; align-content: start; }.profile-field label { display: flex; align-items: center; justify-content: space-between; margin-bottom: 7px; font-size: 13px; font-weight: 650; }.profile-optional { color: #95a199; font-size: 12px; font-weight: 500; }.profile-input { width: 100%; height: 42px; padding: 0 12px; outline: none; border: 1px solid #d9e2dc; border-radius: 10px; background: white; color: #17211b; }.profile-input::placeholder { color: #a3ada6; }.profile-input:focus { border-color: #91b6a0; box-shadow: 0 0 0 3px rgba(66,128,87,.09); }.profile-field small { display: block; margin-top: 6px; color: #94a097; font-size: 11.5px; }.profile-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 3px; }
.profile-btn { min-height: 40px; padding: 0 18px; border: 1px solid #d9e2dc; border-radius: 10px; background: white; color: #34443a; font-weight: 650; transition: .16s ease; display: inline-flex; align-items: center; justify-content: center; gap: 6px; cursor: pointer; }.profile-btn:hover { background: #f8faf9; border-color: #cbd8d0; }.profile-btn-primary { border-color: #1f5d3a; background: #1f5d3a; color: white; box-shadow: 0 7px 14px rgba(31,93,58,.12); }.profile-btn-primary:hover { border-color: #194f31; background: #194f31; }.profile-btn-danger { border-color: #efb8b8; color: #d94242; }.profile-btn-danger:hover { background: #fff2f1; }.profile-settings-list { display: grid; gap: 12px; }.profile-setting-row { min-height: 96px; padding: 18px 20px; border: 1px solid #e5ebe7; border-radius: 15px; background: rgba(255,255,255,.92); display: flex; align-items: center; gap: 16px; }.profile-setting-icon { width: 48px; height: 48px; border-radius: 14px; display: grid; place-items: center; flex: 0 0 auto; background: #f1f5f3; color: #25362c; }.profile-setting-icon.is-danger { background: #fff2f1; color: #d94242; }.profile-setting-info { min-width: 0; flex: 1; }.profile-setting-info strong { display: block; margin-bottom: 4px; font-size: 14px; }.profile-setting-info span { color: #6f7d74; font-size: 12.5px; }
.profile-modal-layer { position: fixed; inset: 0; z-index: 100; padding: 24px; background: rgba(19,30,23,.28); backdrop-filter: blur(3px); display: flex; align-items: center; justify-content: center; }.profile-modal { width: min(92vw,520px); max-height: calc(100vh - 28px); overflow: auto; border: 1px solid rgba(218,228,221,.9); border-radius: 18px; background: white; box-shadow: 0 22px 70px rgba(22,45,32,.17); }.profile-modal-large { width: min(90vw,560px); }.profile-modal-head { padding: 20px 22px 12px; display: flex; align-items: flex-start; gap: 12px; }.profile-modal-head > div:first-child { flex: 1; }.profile-modal-head h3 { margin: 0; font-size: 19px; }.profile-modal-head p { margin: 6px 0 0; color: #6f7d74; font-size: 12.5px; line-height: 1.5; }.profile-close { width: 34px; height: 34px; border: 0; border-radius: 9px; background: transparent; color: #4c5951; cursor: pointer; }.profile-modal-body { padding: 10px 22px 24px; }.profile-modal-foot { padding: 14px 22px; border-top: 1px solid #e5ebe7; border-radius: 0 0 18px 18px; background: #fbfcfb; display: flex; justify-content: space-between; gap: 10px; }.profile-modal-right { display: flex; gap: 10px; }.avatar-edit-layout { display: flex; gap: 32px; }.crop-section { flex: 1; display: flex; flex-direction: column; align-items: center; }.preview-section { width: 170px; padding-left: 32px; border-left: 1px solid #e5ebe7; display: flex; flex-direction: column; align-items: center; justify-content: center; }.crop-stage { width: 260px; height: 260px; position: relative; overflow: hidden; border: 1px solid #dfe7e2; border-radius: 12px; background: #e9efeb; touch-action: none; }.crop-stage.is-draggable { cursor: grab; }.crop-stage.is-dragging { cursor: grabbing; }.crop-image { position: absolute; inset: -10%; background: linear-gradient(140deg,rgba(8,36,24,.12),rgba(255,255,255,.12)),linear-gradient(145deg,#edf5f0,#dbeae1) center/cover; background-position: center; background-size: cover; transition: transform .08s linear; }.crop-placeholder { position: absolute; inset: 0; display: grid; place-items: center; color: #2b6744; font-size: 82px; font-weight: 780; pointer-events: none; }.crop-mask { position: absolute; inset: 10%; pointer-events: none; border: 2px solid white; border-radius: 50%; box-shadow: 0 0 0 999px rgba(7,18,12,.35),0 0 0 1px rgba(0,0,0,.08); }.crop-mask::before,.crop-mask::after { content: ''; position: absolute; background: rgba(255,255,255,.42); }.crop-mask::before { left: 33.33%; top: 0; bottom: 0; width: 1px; box-shadow: calc((100% - 1px)/3) 0 rgba(255,255,255,.42); }.crop-mask::after { top: 33.33%; left: 0; right: 0; height: 1px; box-shadow: 0 calc((100% - 1px)/3) rgba(255,255,255,.42); }.zoom-row { width: 260px; margin-top: 16px; display: grid; grid-template-columns: 28px 1fr 28px; gap: 12px; align-items: center; }.zoom-row input[type='range'] { width: 100%; accent-color: #1f5d3a; cursor: pointer; }.zoom-btn { width: 28px; height: 28px; border: 1px solid #d9e2dc; border-radius: 50%; background: white; color: #5f6e64; display: grid; place-items: center; cursor: pointer; }.preview-title { margin-bottom: 16px; color: #7b887f; font-size: 13px; font-weight: 600; }.preview-circle-large { width: 110px; height: 110px; position: relative; overflow: hidden; margin-bottom: 20px; border: 2px solid white; border-radius: 50%; background: linear-gradient(145deg,#edf5f0,#dbeae1); box-shadow: 0 6px 18px rgba(0,0,0,.08),0 0 0 1px #e5ebe7; display: grid; place-items: center; color: #2b6744; font-size: 42px; font-weight: 780; }.preview-image { position: absolute; inset: -10%; background: linear-gradient(145deg,#edf5f0,#dbeae1) center/cover; background-position: center; background-size: cover; }.preview-circle-large > span { position: relative; z-index: 1; }.upload-tip { margin-bottom: 14px; color: #98a29b; font-size: 11.5px; line-height: 1.5; text-align: center; }.upload-btn { width: 100%; padding: 0; font-size: 13px; }.password-fields { display: grid; gap: 15px; }.password-input { position: relative; }.password-input .profile-input { padding-right: 42px; }.eye-btn { position: absolute; top: 50%; right: 8px; width: 30px; height: 30px; transform: translateY(-50%); border: 0; border-radius: 8px; background: transparent; color: #728077; display: grid; place-items: center; cursor: pointer; }.strength { margin-top: 7px; display: grid; grid-template-columns: repeat(4,1fr); gap: 4px; }.strength span { height: 4px; border-radius: 999px; background: #e7ece9; }.strength span.on { background: #55a46f; }.password-rules { margin-top: 9px; color: #8b9890; font-size: 11.5px; }.profile-toast { position: fixed; left: 50%; bottom: 28px; z-index: 200; padding: 10px 14px; border-radius: 10px; background: #15221a; color: white; box-shadow: 0 10px 30px rgba(0,0,0,.18); opacity: 0; pointer-events: none; transform: translate(-50%,20px); transition: .22s ease; font-size: 13px; }.profile-toast.show { opacity: 1; transform: translate(-50%,0); }
@media (max-width: 720px) { .profile-page { padding: 24px 14px 80px; }.profile-page-head h1 { font-size: 27px; }.profile-card-head { padding: 20px 18px 0; }.profile-body { display: block; padding: 18px; }.profile-side { display: grid; grid-template-columns: auto 1fr; align-items: center; column-gap: 14px; margin-bottom: 18px; padding: 0 0 18px; border-right: 0; border-bottom: 1px solid #e5ebe7; }.profile-avatar-wrap { margin: 0; }.profile-avatar { width: 72px; height: 72px; border-radius: 20px; font-size: 25px; }.profile-identity strong { font-size: 15px; }.profile-setting-row { min-height: 88px; padding: 16px; }.profile-setting-row .profile-btn { padding: 0 13px; }.avatar-edit-layout { flex-direction: column; gap: 24px; }.preview-section { width: 100%; padding: 24px 0 0; border-top: 1px solid #e5ebe7; border-left: 0; }.preview-circle-large { width: 90px; height: 90px; }.profile-modal-foot { flex-direction: column; }.profile-modal-right { display: grid; grid-template-columns: 1fr 1fr; }.profile-modal-foot > .profile-btn { width: 100%; } }
@media (max-width: 480px) { .profile-actions { display: grid; grid-template-columns: 1fr 1fr; }.profile-actions .profile-btn { width: 100%; }.profile-setting-row { align-items: flex-start; flex-wrap: wrap; }.profile-setting-info { min-width: calc(100% - 70px); }.profile-setting-row > .profile-btn { margin-left: 64px; } }
</style>
