<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../auth'

const route = useRoute()
const router = useRouter()

const form = reactive({
  account: '',
  password: ''
})

const submitting = ref(false)

async function submit() {
  if (!form.account.trim() || !form.password) {
    ElMessage.warning('请输入账号与密码')
    return
  }
  submitting.value = true
  try {
    await login(form.account.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    router.replace(redirect)
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="brand">外包点评 · 管理后台</div>
      <div class="sub">仅限内部运营人员登录，所有操作均写入审计日志</div>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="账号">
          <el-input
            v-model="form.account"
            placeholder="请输入管理员账号"
            autocomplete="username"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="submitting" @click="submit">
          登录
        </el-button>
      </el-form>

      <p class="hint">账号密码由后台种子配置（ADMIN_SEED_ACCOUNT / ADMIN_SEED_PASSWORD）。</p>
    </div>
  </div>
</template>

<style scoped>
.submit {
  width: 100%;
  margin-top: 6px;
}

.hint {
  margin: 18px 0 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.6;
}
</style>
