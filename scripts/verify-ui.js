async (page) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole('link', { name: 'API 联调', exact: true }).click();
  await page.getByRole('combobox', { name: '目标部门', exact: true }).selectOption({ label: '研发中心' });
  await page.getByRole('button', { name: '创建上下文 / 切换部门', exact: true }).click();
  await page.locator('.response-code').waitFor();
  const first = JSON.parse(await page.locator('.response-code').innerText());
  await page.getByRole('button', { name: '执行校验', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.response-code')?.textContent?.includes('"allowed"'));
  const allowed = JSON.parse(await page.locator('.response-code').innerText());
  if (allowed.allowed !== true) throw new Error('Initial authorization failed');
  await page.getByRole('combobox', { name: '目标部门', exact: true }).selectOption({ label: '产品设计部' });
  await page.getByRole('button', { name: '创建上下文 / 切换部门', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.response-code')?.textContent?.includes('"contextToken"'));
  const next = JSON.parse(await page.locator('.response-code').innerText());
  if (next.departmentId === first.departmentId || next.permissions.includes('project:create')) throw new Error('Department isolation failed');
  await page.getByRole('combobox', { name: '选择当前或历史上下文', exact: true }).selectOption(first.contextToken);
  await page.getByRole('button', { name: '执行校验', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.response-code')?.textContent?.includes('CONTEXT_INVALID'));
  const invalid = JSON.parse(await page.locator('.response-code').innerText());
  if (invalid.code !== 'CONTEXT_INVALID') throw new Error('Old context was not rejected');
  await page.screenshot({ path: 'output/playwright/context-invalid.png', fullPage: true });
  await page.getByRole('link', { name: '权限概览', exact: true }).click();
  await page.screenshot({ path: 'output/playwright/dashboard-desktop.png', fullPage: true });
  const viewports = [[375, 812], [768, 1024], [1440, 1000]];
  const sizes = [];
  for (const [width, height] of viewports) {
    await page.setViewportSize({ width, height });
    if (width <= 760) await page.waitForFunction(() => document.querySelector('.sidebar').getBoundingClientRect().right <= 0);
    const size = await page.evaluate(() => ({ viewport: innerWidth, content: document.documentElement.scrollWidth }));
    if (size.content > size.viewport) throw new Error('Page overflows viewport at ' + width);
    sizes.push(size);
    if (width === 375) await page.screenshot({ path: 'output/playwright/dashboard-mobile.png', fullPage: true });
  }
  const images = await page.evaluate(() => Array.from(document.images).every(image => image.complete && image.naturalWidth > 0));
  if (!images) throw new Error('Brand asset did not render');
  return { initialAllowed: allowed.allowed, oldContext: invalid.code, viewports: sizes, assetsLoaded: images };
}
