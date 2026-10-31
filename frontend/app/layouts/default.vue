<script setup lang="ts">
const auth = useAuth()
const menuOpen = ref(false)
const route = useRoute()

onMounted(() => {
  auth.init()
})

watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false
  }
)

async function logout() {
  await auth.logout('/')
}
</script>

<template>
  <div class="site-shell">
    <a class="skip-link" href="#main-content">跳到正文</a>

    <header class="site-header">
      <div class="wrap header-inner">
        <NuxtLink to="/" class="brand" aria-label="外包点评首页">
          外包点评
          <span>WAIGO · PINGDIAN</span>
        </NuxtLink>

        <button
          type="button"
          class="menu-toggle"
          :aria-expanded="menuOpen"
          aria-controls="site-nav"
          @click="menuOpen = !menuOpen"
        >
          {{ menuOpen ? '收起菜单' : '菜单' }}
        </button>

        <nav id="site-nav" class="site-nav" :class="{ open: menuOpen }" aria-label="主导航">
          <form class="header-search" action="/search" method="get" role="search">
            <label class="sr-only" for="header-keyword">搜索公司</label>
            <input
              id="header-keyword"
              name="keyword"
              type="search"
              placeholder="搜公司名 / 简称"
              autocomplete="off"
            />
            <button type="submit">搜索</button>
          </form>
          <NuxtLink to="/rankings">榜单</NuxtLink>
          <NuxtLink v-if="auth.isLoggedIn.value" to="/me/reviews">我的</NuxtLink>
          <NuxtLink v-else to="/login">登录</NuxtLink>
          <NuxtLink v-if="auth.isLoggedIn.value" to="/me/notifications">通知</NuxtLink>
          <NuxtLink to="/help">帮助反馈</NuxtLink>
          <NuxtLink to="/terms">用户协议</NuxtLink>
          <NuxtLink to="/privacy">隐私政策</NuxtLink>
          <NuxtLink to="/report-policy">举报与申诉</NuxtLink>
          <button
            v-if="auth.isLoggedIn.value"
            type="button"
            class="nav-text-button"
            @click="logout"
          >
            退出
          </button>
        </nav>
      </div>
    </header>

    <div id="main-content">
      <slot />
    </div>

    <footer class="site-footer">
      <div class="wrap footer-inner">
        <div class="footer-brand">
          <div class="brand brand-footer">外包点评 <span>WAIGO · PINGDIAN</span></div>
          <p>以统一社会信用代码为锚，把经在职佐证核验的外包工作经历聚合到公司档案上。</p>
        </div>
        <div class="footer-col">
          <div class="mono">协议 · LEGAL</div>
          <NuxtLink to="/terms">用户协议</NuxtLink>
          <NuxtLink to="/privacy">隐私政策</NuxtLink>
          <NuxtLink to="/report-policy">举报与申诉渠道说明</NuxtLink>
        </div>
        <div class="footer-col">
          <div class="mono">我的 · ACCOUNT</div>
          <NuxtLink to="/login">登录 / 注册</NuxtLink>
          <NuxtLink to="/me/verification">我的核验</NuxtLink>
          <NuxtLink to="/me/reviews">我的点评</NuxtLink>
          <NuxtLink to="/me/notifications">通知中心</NuxtLink>
        </div>
        <div class="footer-col">
          <div class="mono">渠道 · CONTACT</div>
          <NuxtLink to="/appeal">企业申诉入口</NuxtLink>
          <NuxtLink to="/report">用户举报</NuxtLink>
          <NuxtLink to="/report-company">上报公司</NuxtLink>
          <NuxtLink to="/help">帮助与反馈</NuxtLink>
        </div>
      </div>
      <div class="wrap footer-note">
        <p>
          评价内容由发布者承担法律责任，平台仅提供信息存储与展示的技术服务。
          平台不接受任何形式的付费删帖、付费置顶或付费降权。
        </p>
        <p>
          本平台部署于境外节点，国内访问速度与部分搜索引擎收录可能受到限制。
          平台不对单条评价的绝对真实性作出担保，请自行判断并承担相应决策风险。
        </p>
      </div>
    </footer>
  </div>
</template>
