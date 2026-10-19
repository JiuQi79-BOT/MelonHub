import { ref } from 'vue'

// Keep in step with the phone breakpoint in style.css.
const media = window.matchMedia('(max-width: 768px)')
export const isPhone = ref(media.matches)
media.addEventListener('change', e => { isPhone.value = e.matches })

let observer: MutationObserver | undefined, frame = 0

/**
 * The phone layout shows table rows as cards; antd does not repeat column titles on body cells, so copy them over.
 * Cells showing only a placeholder are flagged so cards can skip them.
 */
function labelCells() {
  frame = 0
  for (const table of document.querySelectorAll('.ant-table')) {
    const head = table.querySelector('thead > tr:last-child')
    if (!head) continue
    const titles: string[] = []
    for (const th of Array.from(head.children) as HTMLTableCellElement[]) {
      const title = th.textContent?.trim() ?? ''
      for (let i = 0; i < Math.max(1, th.colSpan); i++) titles.push(title)
    }
    for (const row of table.querySelectorAll('tbody > tr.ant-table-row')) {
      let column = 0
      for (const cell of Array.from(row.children) as HTMLTableCellElement[]) {
        const title = titles[column] ?? ''
        if (cell.dataset.label !== title) cell.dataset.label = title
        const text = cell.textContent?.trim() ?? ''
        cell.toggleAttribute('data-empty', text === '—' || (!text && !cell.children.length))
        column += Math.max(1, cell.colSpan)
      }
    }
  }
}

/** Tables render in drawers and modals teleported to body, so watch the whole document while the phone layout is active. */
export function labelTables(active: boolean) {
  if (active && !observer) {
    observer = new MutationObserver(() => { frame ||= requestAnimationFrame(labelCells) })
    observer.observe(document.body, { childList: true, characterData: true, subtree: true })
    labelCells()
  } else if (!active && observer) {
    observer.disconnect()
    observer = undefined
    cancelAnimationFrame(frame)
    frame = 0
  }
}
