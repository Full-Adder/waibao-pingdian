<script setup lang="ts">
import type { FeedbackSubmitResult } from '~/types/api'

useSeoMeta({ title: '帮助与反馈 · 外包点评', description: '提交帮助与反馈工单，平台承诺 48 小时内首次响应。' })

const api = useApi()

const category = ref('OTHER')
const contact = ref('')
const content = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const result = ref<FeedbackSubmitResult | null>(null)

const contentLength = computed(() => [...content.value].length)

async function submit() {
  errorMessage.value = ''
  result.value = null
  if (contentLength.value < 10) {
    errorMessage.value = '反馈内容至少 10 字'
    return
  }
  if (contentLength.value > 2000) {
    errorMessage.value = '反馈内容不能超过 2000 字'
    return
  }

  submitting.value = true
  try {
    result.value = await api.post<FeedbackSubmitResult>('/api/feedback', {
      category: category.value,
      contact: contact.value.trim() || null,
      content: content.value.trim()
    })
    content.value = ''
    contact.value = ''
  } catch (error) {
    errorMessage.value = (error as { message?: string }).message || '反馈提交失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="wrap page">
    <div class="mono">HELP &amp; FEEDBACK · 帮助与反馈</div>
    <h1>帮助与反馈</h1>
    <p class="meta">
      提交后会生成工单进入后台处理。平台承诺 48 小时内首次响应；联系方式会加密保存，仅用于回复与核实。
    </p>

    <div class="two-up">
      <section class="paper-card form-card">
        <div class="section-head">
          <h2>提交反馈</h2>
          <span class="mono">TICKET · 48H 首次响应</span>
        </div>

        <div v-if="result" class="success-card">
          <h2>工单已创建</h2>
          <p>
            工单号：<b class="mono-data">{{ result.ticketId }}</b><br />
            当前状态：{{ result.status === 'PENDING' ? '待处理' : result.status }}
          </p>
          <p>平台将在 48 小时内首次响应。请保留工单号以便后续查询。</p>
          <button type="button" class="button primary" @click="result = null">再提交一条</button>
        </div>

        <form v-else class="form-grid" @submit.prevent="submit">
          <div class="field">
            <label for="feedback-category">问题分类</label>
            <select id="feedback-category" v-model="category" class="select">
              <option v-for="(label, code) in FEEDBACK_CATEGORY_LABELS" :key="code" :value="code">
                {{ label }}
              </option>
            </select>
          </div>

          <div class="field">
            <label for="feedback-contact">联系方式（选填）</label>
            <input
              id="feedback-contact"
              v-model="contact"
              class="input"
              type="text"
              autocomplete="off"
              placeholder="手机号或邮箱，仅用于回复"
            />
          </div>

          <div class="field form-span">
            <label for="feedback-content">问题描述（10–2000 字）</label>
            <textarea
              id="feedback-content"
              v-model="content"
              class="input textarea"
              rows="7"
              maxlength="2000"
              placeholder="请说明遇到的问题、页面地址与期望处理方式。请勿填写他人隐私信息。"
            />
            <span class="hint">当前 {{ contentLength }} 字</span>
          </div>

          <div class="field form-actions">
            <button class="button primary" type="submit" :disabled="submitting">
              {{ submitting ? '提交中…' : '提交反馈工单' }}
            </button>
          </div>
        </form>

        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
      </section>

      <aside class="paper-card form-card">
        <div class="section-head">
          <h2>常见问题</h2>
          <span class="mono">FAQ</span>
        </div>
        <dl class="faq-list">
          <dt>为什么我不能发评？</dt>
          <dd>发评需要完成实名认证，并提交与公司档案一致的在职佐证且审核通过。可在「我的核验」查看状态。</dd>

          <dt>评价被驳回后还能改吗？</dt>
          <dd>可在「我的点评」查看驳回原因。非红线违规可申诉一次；红线违规不可申诉。</dd>

          <dt>为什么评价没有显示在公司页？</dt>
          <dd>机器初审与人工复核期间评价暂不展示，也不计入综合分；审核通过后自动恢复展示。</dd>

          <dt>企业如何申诉？</dt>
          <dd>通过<a href="/appeal">企业申诉</a>提交营业执照、公章申诉函与 USCC；主体核验通过后才进入争议流程。</dd>

          <dt>如何举报违规内容？</dt>
          <dd>登录后通过<a href="/report">用户举报</a>选择结构化原因码并提交。同一对象 24 小时内限报 1 次。</dd>
        </dl>
      </aside>
    </div>
  </main>
</template>
