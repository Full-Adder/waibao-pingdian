<script setup lang="ts">
import type { LoginResult, SmsSendResult } from '~/types/api'

useSeoMeta({ title: '登录 / 注册 · 外包点评', robots: 'noindex' })

const api = useApi()
const auth = useAuth()
const route = useRoute()

const phone = ref('')
const code = ref('')
const acceptedTerms = ref(false)
const sending = ref(false)
const submitting = ref(false)
const countdown = ref(0)
const sent = ref(false)
const errorMessage = ref('')
const isDev = import.meta.dev
let timer: ReturnType<typeof setInterval> | null = null

const redirectTarget = computed(() => {
  const raw = Array.isArray(route.query.redirect) ? route.query.redirect[0] : route.query.redirect
  const target = String(raw ?? '')
  return target.startsWith('/') && !target.startsWith('//') ? target : '/me/reviews'
})

const phoneValid = computed(() => /^1[3-9]\d{9}$/.test(phone.value.trim()))

const redirectReason = computed(() => {
  const raw = Array.isArray(route.query.reason) ? route.query.reason[0] : route.query.reason
  return String(raw ?? '')
})

onMounted(async () => {
  await auth.init()
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

function startCountdown(seconds: number) {
  countdown.value = Math.max(1, seconds)
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    countdown.value = Math.max(0, countdown.value - 1)
    if (countdown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function sendCode() {
  errorMessage.value = ''
  if (!phoneValid.value) {
    errorMessage.value = '请输入正确的 11 位手机号'
    return
  }
  sending.value = true
  try {
    const result = await api.post<SmsSendResult>('/api/auth/sms/send', {
      phone: phone.value.trim(),
      scene: 'LOGIN'
    })
    sent.value = true
    startCountdown(result?.resendAfterSeconds ?? 60)
  } catch (error) {
    errorMessage.value = (error as { message?: string }).message || '验证码发送失败，请稍后再试'
  } finally {
    sending.value = false
  }
}

async function submit() {
  errorMessage.value = ''
  if (!phoneValid.value) {
    errorMessage.value = '请输入正确的 11 位手机号'
    return
  }
  if (!/^\d{6}$/.test(code.value.trim())) {
    errorMessage.value = '请输入 6 位数字验证码'
    return
  }
  if (!acceptedTerms.value) {
    errorMessage.value = '请先阅读并同意用户协议与隐私政策'
    return
  }

  submitting.value = true
  try {
    const result = await api.post<LoginResult>('/api/auth/login', {
      phone: phone.value.trim(),
      code: code.value.trim(),
      acceptedTerms: true
    })
    auth.setSession(result)
    await navigateTo(redirectTarget.value)
  } catch (error) {
    errorMessage.value = (error as { message?: string }).message || '登录失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="wrap page auth-page">
    <div class="auth-layout">
      <section class="paper-card auth-card">
        <div class="mono">ACCOUNT · 登录 / 注册</div>
        <h1>手机号登录</h1>
        <p class="auth-lead">
          首次登录会自动创建账号，并生成固定匿名别名。发布评价前仍需完成实名与在职佐证核验。
        </p>

        <div v-if="isDev" class="note" style="margin: 14px 0">
          Mock 模式提示：开发环境下短信验证码固定为 <b class="mono-data">123456</b>，
          发送后将写入后端日志，不会真实下发短信。
        </div>

        <div v-if="redirectReason" class="note warn" role="status">
          {{ redirectReason === 'expired' ? '登录状态已失效，请重新登录。' : '该操作需要登录，请先登录后再继续。' }}
        </div>

        <form class="auth-form" @submit.prevent="submit">
          <div class="field">
            <label for="login-phone">手机号</label>
            <input
              id="login-phone"
              v-model="phone"
              class="input"
              type="tel"
              inputmode="numeric"
              maxlength="11"
              autocomplete="tel"
              placeholder="请输入 11 位手机号"
            />
          </div>

          <div class="field">
            <label for="login-code">短信验证码</label>
            <div class="code-row">
              <input
                id="login-code"
                v-model="code"
                class="input"
                type="text"
                inputmode="numeric"
                maxlength="6"
                autocomplete="one-time-code"
                placeholder="6 位数字验证码"
              />
              <button
                type="button"
                class="button"
                :disabled="sending || countdown > 0 || !phoneValid"
                @click="sendCode"
              >
                {{ countdown > 0 ? `${countdown}s 后重发` : sending ? '发送中…' : '获取验证码' }}
              </button>
            </div>
            <span v-if="sent" class="hint">验证码已发送，5 分钟内有效，仅可使用一次。</span>
          </div>

          <label class="check-line">
            <input v-model="acceptedTerms" type="checkbox" />
            <span>
              我已阅读并同意
              <NuxtLink to="/terms" target="_blank">《用户协议》</NuxtLink>
              与
              <NuxtLink to="/privacy" target="_blank">《隐私政策》</NuxtLink>
            </span>
          </label>

          <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>

          <button class="button primary auth-submit" type="submit" :disabled="submitting">
            {{ submitting ? '登录中…' : '登录 / 注册' }}
          </button>
        </form>
      </section>

      <aside class="paper-card auth-side">
        <div class="mono">SINGLE DEVICE · 单设备登录</div>
        <h2>同一时间只保留一台设备</h2>
        <p>
          账号默认单设备登录。在新设备登录会使此前签发的登录凭证立即失效，
          旧设备需要重新获取验证码登录。这是为了避免账号被出借用于代发评价或刷评。
        </p>
        <h2>登录后可以做什么</h2>
        <ul>
          <li>提交实名信息与在职佐证，申请「在职中 / 曾在职」核验徽标；</li>
          <li>对已核验公司发布评价，正文至少 20 字，六个维度至少填 3 项；</li>
          <li>在「我的点评」查看审核状态、编辑、撤回或申诉；</li>
          <li>接收争议、举证、裁定、核验与申诉的站内必达通知。</li>
        </ul>
        <p class="hint">
          登录凭证保存在本机浏览器 localStorage，仅用于在请求头携带 Bearer token；
          平台不会保存身份证号明文。
        </p>
      </aside>
    </div>
  </main>
</template>
