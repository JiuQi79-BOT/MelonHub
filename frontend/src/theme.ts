import { computed, ref, watch } from 'vue'
import { theme } from 'ant-design-vue'

export type ThemeMode = 'light' | 'dark'
const STORAGE = 'melonhub-theme'
const media = window.matchMedia('(prefers-color-scheme: dark)')
const saved = localStorage.getItem(STORAGE)

export const themeMode = ref<ThemeMode>(saved === 'light' || saved === 'dark' ? saved : media.matches ? 'dark' : 'light')
export const isDark = computed(() => themeMode.value === 'dark')

function apply(mode: ThemeMode) {
  const root = document.documentElement
  root.dataset.theme = mode
  root.style.colorScheme = mode
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', mode === 'dark' ? '#0b0b0d' : '#f7f7f8')
}
apply(themeMode.value)
watch(themeMode, apply)
// Follow the operating system until the user has picked a theme explicitly.
media.addEventListener('change', e => { if (!localStorage.getItem(STORAGE)) themeMode.value = e.matches ? 'dark' : 'light' })

export function toggleTheme() {
  themeMode.value = isDark.value ? 'light' : 'dark'
  localStorage.setItem(STORAGE, themeMode.value)
}

const fontFamily = "'Segoe UI Variable Text', 'Segoe UI', -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei UI', 'Microsoft YaHei', 'Helvetica Neue', Arial, sans-serif"

// Keep in step with the CSS variables in style.css; antd derives hover/active shades from these seeds.
const palettes = {
  light: {
    base: '#ffffff', text: '#18181b', layout: '#f7f7f8', container: '#ffffff', elevated: '#ffffff', border: '#e4e4e7', borderSoft: '#ececef',
    fillAlter: '#fafafa', link: '#0b7555', linkHover: '#13976d',
    menu: { text: '#52525b', hover: '#18181b', hoverBg: 'rgba(24, 24, 27, 0.04)', activeBg: 'rgba(24, 24, 27, 0.07)', selected: '#18181b', selectedBg: 'rgba(24, 24, 27, 0.07)', group: '#a1a1aa' },
    shadow: '0 12px 32px -8px rgba(24, 24, 27, 0.16), 0 2px 6px -1px rgba(24, 24, 27, 0.06)',
  },
  dark: {
    base: '#0b0b0d', text: '#fafafa', layout: '#0b0b0d', container: '#111113', elevated: '#18181b', border: '#2e2e33', borderSoft: '#232327',
    fillAlter: '#18181b', link: '#34d399', linkHover: '#6ee7b7',
    menu: { text: '#a1a1aa', hover: '#fafafa', hoverBg: 'rgba(255, 255, 255, 0.05)', activeBg: 'rgba(255, 255, 255, 0.08)', selected: '#fafafa', selectedBg: 'rgba(255, 255, 255, 0.08)', group: '#71717a' },
    shadow: '0 16px 40px -8px rgba(0, 0, 0, 0.6), 0 2px 6px -1px rgba(0, 0, 0, 0.4)',
  },
}

export const antTheme = computed(() => {
  const p = isDark.value ? palettes.dark : palettes.light
  return {
    algorithm: isDark.value ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      // #0d8460 keeps white button text above 4.5:1 contrast while matching the shield in the logo.
      colorPrimary: '#0d8460',
      colorSuccess: '#16a34a',
      colorWarning: '#d97706',
      colorError: '#dc2626',
      colorInfo: '#2563eb',
      colorLink: p.link,
      colorLinkHover: p.linkHover,
      colorLinkActive: p.link,
      colorTextBase: p.text,
      colorBgBase: p.base,
      colorBgLayout: p.layout,
      colorBgContainer: p.container,
      colorBgElevated: p.elevated,
      colorBorder: p.border,
      colorBorderSecondary: p.borderSoft,
      colorFillAlter: p.fillAlter,
      fontFamily,
      fontSize: 13,
      borderRadius: 6,
      borderRadiusLG: 8,
      borderRadiusSM: 4,
      borderRadiusXS: 2,
      wireframe: false,
      boxShadow: p.shadow,
      boxShadowSecondary: p.shadow,
    },
    components: {
      Menu: {
        colorItemBg: 'transparent', colorSubItemBg: 'transparent', colorItemText: p.menu.text, colorItemTextHover: p.menu.hover,
        colorItemBgHover: p.menu.hoverBg, colorItemBgActive: p.menu.activeBg, colorItemTextSelected: p.menu.selected,
        colorItemBgSelected: p.menu.selectedBg, colorGroupTitle: p.menu.group, radiusItem: 6, radiusSubMenuItem: 4,
        colorActiveBarBorderSize: 0, colorActiveBarWidth: 0, itemMarginInline: 8,
      },
      Layout: { colorBgHeader: p.container, colorBgBody: p.container, colorBgTrigger: p.container },
    },
  }
})
