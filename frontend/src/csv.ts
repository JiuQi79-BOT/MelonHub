/** Download the selected list as CSV; keep spreadsheet apps from interpreting cell text as formulas. */
export function downloadCsv(name: string, columns: string[], rows: unknown[][]) {
  const cell = (value: unknown) => {
    let text = value === null || value === undefined ? '' : String(value)
    if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`
    return `"${text.replace(/"/g, '""')}"`
  }
  const csv = [columns, ...rows].map(row => row.map(cell).join(',')).join('\r\n')
  const link = document.createElement('a')
  link.href = URL.createObjectURL(new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' }))
  link.download = `melonhub-${name}-${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}
