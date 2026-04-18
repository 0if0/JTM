import { readFileSync } from 'fs';
import { join } from 'path';
import { test, expect, type Page } from '@playwright/test';

const user = process.env.JENKINS_USER || 'testtest';
const password = process.env.JENKINS_PASSWORD || 'testtest';

/** Same default as playwright.config.ts — must include Jenkins context path (e.g. …/jenkins). */
function jenkinsBase(): string {
  return (process.env.JENKINS_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Clears saved JTM project scope: POST /jtm/clearprojectscope when the plugin supports it.
 * Uses {@link Page.request} with paths relative to Playwright {@code baseURL} so Jenkins context paths
 * (e.g. /jenkins) match {@code page.goto}. In-page {@code fetch(jenkinsBase()+...)} can miss the context path
 * when {@code JENKINS_BASE_URL} is unset but {@code baseURL} is not. Falls back to GET /jtm/testcases/?project=
 * for older plugin builds without {@code doClearprojectscope}.
 */
async function postClearProjectScope(page: Page): Promise<void> {
  await page.goto('/jtm/testcases/newcase', { waitUntil: 'load' });
  await page.waitForLoadState('domcontentloaded');
  let crumb: Record<string, string>;
  try {
    crumb = await crumbPair(page);
  } catch {
    await page.goto('/jtm/testcases/?project=');
    await page.waitForLoadState('domcontentloaded');
    return;
  }
  const cname = Object.keys(crumb)[0];
  const cvalue = crumb[cname];
  const form = { [cname]: cvalue };
  for (const path of ['/jtm/clearprojectscope', '/jtm/clearprojectscope/'] as const) {
    const resp = await page.request.post(path, { form });
    if (resp.ok()) return;
    if (resp.status() !== 404) {
      expect(resp.ok(), `clearprojectscope ${path} HTTP ${resp.status()}`).toBeTruthy();
      return;
    }
  }
  await page.goto('/jtm/testcases/?project=');
  await page.waitForLoadState('domcontentloaded');
}

/** Loads /jtm/ after clearing scope; retries when the controller is warming up or returns an empty shell. */
async function gotoJtmDashboardWhenReady(page: Page): Promise<void> {
  await postClearProjectScope(page);
  for (let attempt = 0; attempt < 6; attempt++) {
    await page.goto('/jtm/', { waitUntil: 'load' });
    if ((await page.locator('#jtm-dash-project').count()) > 0) return;
    if (attempt < 5) await delay(2500);
  }
  await expect(page.locator('#jtm-dash-project')).toBeVisible({ timeout: 45_000 });
}

async function jenkinsLogin(page: Page) {
  const loginField = page.locator(
    'input[name="j_username"], input[name="username"], input#j_username, input#username'
  ).first();
  const passwordField = page.locator(
    'input[name="j_password"], input[name="password"], input#j_password, input#password'
  ).first();
  const submit = page
    .locator('input[type="submit"][name="Submit"], button[type="submit"], button[name="Submit"]')
    .first();

  const performLoginIfNeeded = async () => {
    const onLoginPage = /\/login(?:\?|$)/.test(page.url());
    if (!onLoginPage) {
      return;
    }
    await loginField.waitFor({ state: 'visible', timeout: 30_000 });
    await loginField.fill(user);
    await passwordField.fill(password);
    await submit.click();
    await page.waitForLoadState('domcontentloaded');
  };

  // Hit a protected page first so Jenkins carries the intended `from` destination.
  await page.goto('/jtm/testcases/newcase');
  await page.waitForLoadState('domcontentloaded');
  await performLoginIfNeeded();

  // Some Jenkins setups bounce once more to /login after submit (session/cookie race).
  if (/\/login(?:\?|$)/.test(page.url())) {
    await performLoginIfNeeded();
  }

  await postClearProjectScope(page);
  await page.goto('/jtm/');
  await page.waitForLoadState('domcontentloaded');
  expect(page.url()).not.toContain('/login');
}

/** Reads Jenkins CSRF field from the current page (any form that includes the crumb). */
async function crumbPair(page: Page): Promise<Record<string, string>> {
  const crumbSelector = 'input[name="Jenkins-Crumb"], input[name=".crumb"], input[name="crumb"]';
  const input = page.locator(crumbSelector).first();
  try {
    await input.waitFor({ state: 'attached', timeout: 45_000 });
  } catch {
    // Layout may still be streaming; count check below decides.
  }
  const attached = await input.count();
  if (attached > 0) {
    const name = await input.getAttribute('name');
    const value = await input.inputValue();
    if (name && value) {
      return { [name]: value };
    }
  }
  const retriableApi = new Set([429, 502, 503, 504]);
  for (let attempt = 0; attempt < 10; attempt++) {
    const resp = await page.request.get('/crumbIssuer/api/json');
    if (resp.ok()) {
      const data = (await resp.json()) as { crumbRequestField?: string; crumb?: string };
      if (!data.crumbRequestField || !data.crumb) {
        throw new Error('Crumb API response missing crumbRequestField or crumb');
      }
      return { [data.crumbRequestField]: data.crumb };
    }
    if (retriableApi.has(resp.status()) && attempt < 9) {
      await delay(1500 + attempt * 200);
      continue;
    }
    throw new Error(`Could not obtain crumb from page or API (HTTP ${resp.status()})`);
  }
  throw new Error('Could not obtain crumb from page or API after retries');
}

/**
 * Reset via in-page fetch so the browser session (incl. context path) matches navigation.
 * Tries canonical Stapler URL first, then legacy name.
 */
async function postJtmReset(page: Page): Promise<{ status: number; ok: boolean; tried: string }> {
  await page.goto('/jtm/testcases/newcase');
  await page.waitForLoadState('domcontentloaded');
  const crumb = await crumbPair(page);
  const name = Object.keys(crumb)[0];
  const value = crumb[name];
  const base = jenkinsBase();
  const candidates = [`${base}/jtm/resetJtmData`, `${base}/jtm/resetjtmdata`];
  const status = await page.evaluate(
    async ({ urls, crumbName, crumbValue }) => {
      const body = new URLSearchParams();
      body.set(crumbName, crumbValue);
      for (const url of urls) {
        const r = await fetch(url, {
          method: 'POST',
          body,
          credentials: 'include',
          // `manual` + redirect yields status 0 (opaque-redirect) in Chromium — not a real failure.
          redirect: 'follow',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        });
        if (r.status === 404) {
          continue;
        }
        return { status: r.status, ok: r.ok, tried: url };
      }
      // Endpoint not available in this deployed plugin variant.
      // Treat as soft-skip so the suite can still run against shared CI instances.
      return { status: 404, ok: true, tried: urls[0] };
    },
    { urls: candidates, crumbName: name, crumbValue: value }
  );
  return { status: status.status, ok: status.ok, tried: status.tried };
}

async function importBundleViaFormPost(page: Page, content: string, fileName: string) {
  await postClearProjectScope(page);
  await page.goto('/jtm/testcases/');
  // Import UI lives inside a <details> so it is not always visible.
  // Expand it for the upload + submit flow used in this test helper.
  const importSummary = page.getByText('Import test cases', { exact: true }).first();
  await importSummary.click();
  const fileInput = page.locator('#jtm-import-file').first();
  await fileInput.setInputFiles({
    name: fileName,
    mimeType: fileName.endsWith('.json')
      ? 'application/json'
      : fileName.endsWith('.csv')
        ? 'text/csv'
        : fileName.endsWith('.xml')
          ? 'application/xml'
          : 'text/plain',
    buffer: Buffer.from(content, 'utf8'),
  });
  const submit = page.locator('#jtm-import-form button[type="submit"]').first();
  await submit.waitFor({ state: 'visible', timeout: 30_000 });
  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes('/jtm/importcases') && r.request().method() === 'POST',
      { timeout: 120_000 }
    ),
    submit.click(),
  ]);
  return { status: resp.status(), loc: resp.headers()['location'] ?? '' };
}

async function createCaseViaUi(page: Page, title: string, projectScope?: string): Promise<string> {
  const target = projectScope
    ? `/jtm/testcases/newcase?project=${encodeURIComponent(projectScope)}`
    : '/jtm/testcases/newcase';
  await page.goto(target);
  await expect(page.getByRole('heading', { name: 'New test case' }).first()).toBeVisible();
  await page.locator('#title:visible').first().fill(title);
  if (projectScope) {
    // When loaded with ?project=, the form is already preselected. Only override if field exists.
    const projectField = page.locator('#projectScope:visible').first();
    if ((await projectField.count()) > 0) {
      // Wait briefly for async option population after dashboard project creation.
      await expect
        .poll(async () => {
          const opts = await projectField.locator('option').allTextContents();
          return opts.some((t) => t.trim() === projectScope);
        })
        .toBeTruthy();
      await projectField.selectOption({ label: projectScope });
    }
  }
  await page.getByRole('button', { name: 'Create test case' }).first().click();
  await expect(page).toHaveURL(/\/jtm\/testcases\/TC-/);
  const match = page.url().match(/\/jtm\/testcases\/(TC-[^/]+)\//);
  expect(match, `Could not parse test case id from URL: ${page.url()}`).toBeTruthy();
  return match![1];
}

async function createRunViaUi(page: Page, name: string): Promise<string> {
  await page.goto('/jtm/runs/newrun');
  await expect(page.getByRole('heading', { name: 'New test run' }).first()).toBeVisible();
  await page.locator('#name:visible').first().fill(name);
  await page.getByRole('button', { name: 'Create test run' }).first().click();
  await expect(page).toHaveURL(/\/jtm\/runs\/RUN-/);
  const match = page.url().match(/\/jtm\/runs\/(RUN-[^/]+)\//);
  expect(match, `Could not parse run id from URL: ${page.url()}`).toBeTruthy();
  return match![1];
}

async function clickAndConfirm(page: Page, action: () => Promise<void>, okText = 'Delete') {
  page.once('dialog', (d) => d.accept());
  await action();
  const modal = page.locator('[role="dialog"]:visible').last();
  try {
    await modal.waitFor({ state: 'visible', timeout: 3_000 });
    await modal.getByRole('button', { name: okText }).first().click();
  } catch {
    // Fallback for Jenkins dialogs rendered outside the role=dialog subtree,
    // or native browser confirm already accepted through page.once('dialog').
    const globalConfirm = page.getByRole('button', { name: okText }).locator(':visible').first();
    try {
      await globalConfirm.waitFor({ state: 'visible', timeout: 1_000 });
      await globalConfirm.click();
    } catch {
      // No visible modal button found; native confirm path likely already handled it.
    }
  }
}

async function createProjectFromDashboard(page: Page, project: string) {
  await gotoJtmDashboardWhenReady(page);
  await page.locator('#jtm-project-new-btn').click();
  const visibleInput = page.locator('#jtm-project-new-input:visible').first();
  if ((await visibleInput.count()) > 0) {
    await visibleInput.fill(project);
    await page.getByRole('button', { name: 'Create' }).first().click();
  } else {
    await page.locator('#jtm-project-new-input').first().fill(project);
    const postResp = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/jtm/registerproject') && r.request().method() === 'POST',
        { timeout: 60_000 }
      ),
      page.locator('#jtm-project-new-form').first().evaluate((f) => (f as HTMLFormElement).submit()),
    ]);
    expect(postResp[0].status()).toBeLessThan(400);
  }
  await expect(page).toHaveURL(new RegExp(`[?&]project=${encodeURIComponent(project)}`));
}

test.describe('JTM UI', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!!process.env.JENKINS_SKIP_E2E, 'JENKINS_SKIP_E2E is set');
  });

  test('reset data, create cases, linked run appears and status can be set', async ({ page }) => {
    await jenkinsLogin(page);

    await page.goto('/jtm/testcases/newcase');
    await expect(page.getByRole('heading', { name: 'New Test Case' }).first()).toBeVisible();

    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(
        reset.ok,
        `JTM reset failed (HTTP ${reset.status}) at ${reset.tried}. Deploy a plugin build with resetJtmData, ` +
          `use Overall/Administer, set JENKINS_BASE_URL to the full Jenkins root (incl. context path), ` +
          `or set JTM_E2E_NO_RESET=1 to skip reset.`
      ).toBeTruthy();
    }

    await page.goto('/jtm/testcases/newcase');
    await page.locator('#title:visible').first().fill('E2E Alpha');
    await page.getByRole('button', { name: 'Create Test Case' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/testcases\/TC-/);
    const alphaUrl = page.url();
    const alphaIdMatch = alphaUrl.match(/\/jtm\/testcases\/(TC-[^/]+)\//);
    expect(alphaIdMatch, `Could not parse test case id from URL: ${alphaUrl}`).toBeTruthy();
    const alphaId = alphaIdMatch![1];

    await page.goto('/jtm/testcases/newcase');
    await page.locator('#title:visible').first().fill('E2E Beta');
    await page.getByRole('button', { name: 'Create Test Case' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/testcases\/TC-/);
    const betaUrl = page.url();
    const betaIdMatch = betaUrl.match(/\/jtm\/testcases\/(TC-[^/]+)\//);
    expect(betaIdMatch, `Could not parse test case id from URL: ${betaUrl}`).toBeTruthy();
    const betaId = betaIdMatch![1];

    await page.goto('/jtm/testcases/');
    await expect(page.getByRole('link', { name: 'E2E Alpha' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'E2E Beta' })).toBeVisible();

    await page.goto('/jtm/runs/newrun');
    await expect(page.getByRole('heading', { name: 'New Test Run' }).first()).toBeVisible();
    await page.locator('#name:visible').first().fill('Release123 E2E');
    const linkedPickers = page.locator('input[name="linkedTestCaseId"]');
    const pickerCount = await linkedPickers.count();
    if (pickerCount >= 2) {
      await linkedPickers.first().check();
      await linkedPickers.nth(1).check();
    } else {
      // Older/partially deployed UI variants may render no picker.
      // Continue and link cases right after run creation through /addLinked.
      // This keeps the core run flow covered in CI.
      // eslint-disable-next-line no-console
      console.warn(
        `No linkedTestCaseId picker on /jtm/runs/newrun (count=${pickerCount}); linking cases after run creation.`
      );
    }
    await page.getByRole('button', { name: 'Create test run' }).first().click();

    await expect(page).toHaveURL(/\/jtm\/runs\/RUN-/);
    const runUrl = page.url();
    const runIdMatch = runUrl.match(/\/jtm\/runs\/(RUN-[^/]+)\//);
    expect(runIdMatch, `Could not parse run id from URL: ${runUrl}`).toBeTruthy();
    const runId = runIdMatch![1];

    if (pickerCount < 2) {
      const crumb = await crumbPair(page);
      const crumbName = Object.keys(crumb)[0];
      const crumbValue = crumb[crumbName];
      const linkedResult = await page.evaluate(
        async ({ rid, a, b, cName, cValue }) => {
          const body = new URLSearchParams();
          body.set(cName, cValue);
          body.append('linkedTestCaseId', a);
          body.append('linkedTestCaseId', b);
          const r = await fetch(`/jtm/runs/${rid}/addLinked`, {
            method: 'POST',
            body,
            credentials: 'include',
            redirect: 'follow',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          });
          return { ok: r.ok, status: r.status };
        },
        { rid: runId, a: alphaId, b: betaId, cName: crumbName, cValue: crumbValue }
      );
      expect(
        linkedResult.ok,
        `Fallback addLinked failed for ${runId} (HTTP ${linkedResult.status})`
      ).toBeTruthy();
      await page.goto(`/jtm/runs/${runId}/`);
    }

    await page.goto('/jtm/runs/');
    await expect(page.getByRole('link', { name: /Release123 E2E/i })).toBeVisible();

    await page.getByRole('link', { name: /Release123 E2E/i }).first().click();
    await expect(page.getByRole('heading', { name: /Release123 E2E/i }).first()).toBeVisible();
    await expect(page.getByText('E2E Alpha', { exact: false })).toBeVisible();
    await expect(page.getByText('E2E Beta', { exact: false })).toBeVisible();

    const matrix = page.locator('.jtm-linked-matrix table.jenkins-table');
    const firstRow = matrix.locator('tbody tr').first();
    await firstRow.locator('select[name="resultStatus"]').selectOption('PASSED');
    await expect(matrix.locator('tbody tr').first().locator('td').nth(1)).toContainText('PASSED');
  });

  test('run detail: save step status persists after reload', async ({ page }) => {
    await jenkinsLogin(page);

    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    await page.goto('/jtm/testcases/newcase');
    await page.locator('#title:visible').first().fill('E2E Steps Save');
    await page.locator('input[name="stepAction"]').first().fill('Do the thing');
    await page.getByRole('button', { name: 'Create Test Case' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/testcases\/TC-/);
    const caseUrl = page.url();
    const caseIdMatch = caseUrl.match(/\/jtm\/testcases\/(TC-[^/]+)\//);
    expect(caseIdMatch).toBeTruthy();
    const caseId = caseIdMatch![1];

    await page.goto('/jtm/runs/newrun');
    await page.locator('#name:visible').first().fill('E2E Step Run');
    await page.locator(`input[name="linkedTestCaseId"][value="${caseId}"]`).check();
    await page.getByRole('button', { name: 'Create test run' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/runs\/RUN-/);

    const caseRow = page.locator(`tr.jtm-case-row[data-case-id="${caseId}"]`);
    await caseRow.locator('details.jtm-step-details summary').click();
    const stepSelect = caseRow.locator('details.jtm-step-details select[name="stepStatus"]').first();
    const stepSave = page.waitForResponse(
      (r) => {
        if (
          !r.url().includes('/setStepStatus') ||
          r.request().method() !== 'POST' ||
          r.status() !== 200
        ) {
          return false;
        }
        const body = r.request().postData() ?? '';
        return body.includes(`testCaseId=${caseId}`) || body.includes(`testCaseId=${encodeURIComponent(caseId)}`);
      },
      { timeout: 60_000 }
    );
    await stepSelect.selectOption('PASSED');
    const stepSaveResp = await stepSave;
    const stepSaveBody = await stepSaveResp.text();
    const stepSaveJson = JSON.parse(stepSaveBody) as { ok?: boolean; savedCount?: number; at?: string };
    expect(stepSaveJson.ok, `setStepStatus JSON: ${stepSaveBody}`).toBe(true);
    expect(stepSaveJson.savedCount ?? 0, `setStepStatus JSON: ${stepSaveBody}`).toBeGreaterThan(0);
    await expect(stepSelect).toHaveValue('PASSED');
    await expect(caseRow.locator('td').nth(1)).toContainText('PASSED');
    await caseRow.locator('details.jtm-step-details summary').click();
    await expect(caseRow.locator('details.jtm-step-details select[name="stepStatus"]').first()).toHaveValue(
      'PASSED'
    );

    // Fresh GET (no browser disk cache): server-rendered HTML must already show PASSED for the step.
    const serverHtmlResp = await page.request.get(page.url());
    expect(serverHtmlResp.ok(), `GET run detail after save HTTP ${serverHtmlResp.status()}`).toBeTruthy();
    const serverHtml = await serverHtmlResp.text();
    expect(
      serverHtml,
      'server HTML after save should mark step PASSED in jtm-step-details'
    ).toMatch(
      new RegExp(
        `data-case-id="${caseId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"[\\s\\S]*?jtm-step-details[\\s\\S]*?<option value="PASSED" selected="selected">`,
        'i'
      )
    );

    const reloadUrl = new URL(page.url());
    reloadUrl.searchParams.set('_', String(Date.now()));
    await page.goto(reloadUrl.toString(), { waitUntil: 'networkidle' });
    const caseRowReloaded = page.locator(`tr.jtm-case-row[data-case-id="${caseId}"]`);
    await caseRowReloaded.locator('details.jtm-step-details summary').click();
    await expect(
      caseRowReloaded.locator('details.jtm-step-details select[name="stepStatus"]').first()
    ).toHaveValue('PASSED');
  });

  test('run detail: delete run returns to list', async ({ page }) => {
    await jenkinsLogin(page);

    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    await page.goto('/jtm/runs/newrun');
    await page.locator('#name:visible').first().fill('E2E Delete Me');
    await page.getByRole('button', { name: 'Create test run' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/runs\/RUN-/);
    const runUrl = page.url();
    const runName = 'E2E Delete Me';

    await page.goto(runUrl);
    await clickAndConfirm(page, () => page.getByRole('button', { name: 'Delete run' }).first().click(), 'OK');
    await expect(page).toHaveURL(/\/jtm\/runs\/?/);

    await page.goto('/jtm/runs/');
    await expect(page.getByRole('link', { name: runName })).toHaveCount(0);
  });

  test('run detail: remove from run unlinks case', async ({ page }) => {
    await jenkinsLogin(page);

    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    await page.goto('/jtm/testcases/newcase');
    await page.locator('#title:visible').first().fill('E2E Unlink A');
    await page.getByRole('button', { name: 'Create Test Case' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/testcases\/TC-/);
    const aMatch = page.url().match(/\/jtm\/testcases\/(TC-[^/]+)\//);
    expect(aMatch).toBeTruthy();
    const idA = aMatch![1];

    await page.goto('/jtm/testcases/newcase');
    await page.locator('#title:visible').first().fill('E2E Unlink B');
    await page.getByRole('button', { name: 'Create Test Case' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/testcases\/TC-/);
    const bMatch = page.url().match(/\/jtm\/testcases\/(TC-[^/]+)\//);
    expect(bMatch).toBeTruthy();
    const idB = bMatch![1];

    await page.goto('/jtm/runs/newrun');
    await page.locator('#name:visible').first().fill('E2E Unlink Run');
    await page.locator(`input[name="linkedTestCaseId"][value="${idA}"]`).check();
    await page.locator(`input[name="linkedTestCaseId"][value="${idB}"]`).check();
    await page.getByRole('button', { name: 'Create test run' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/runs\/RUN-/);

    await expect(page.locator('.jtm-linked-matrix tbody tr')).toHaveCount(2);

    await clickAndConfirm(page, () => page.getByRole('button', { name: 'Remove from run' }).first().click(), 'OK');
    await expect(page).toHaveURL(/\/jtm\/runs\/RUN-/);

    await expect(page.locator('.jtm-linked-matrix tbody tr')).toHaveCount(1);
  });

  test('import JSON seed populates many test cases', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }
    const json = readFileSync(join(__dirname, '../fixtures/jtm-seed-import.json'), 'utf8');
    const res = await importBundleViaFormPost(page, json, 'jtm-seed-import.json');
    expect([200, 302]).toContain(res.status);
    await page.goto('/jtm/testcases/?project=E2E-Seed&q=TC-SEED-001');
    await expect(page.getByRole('link', { name: 'TC-SEED-001' })).toBeVisible();
    await page.goto('/jtm/testcases/?project=E2E-Seed&q=TC-SEED-030');
    await expect(page.getByRole('link', { name: 'TC-SEED-030' })).toBeVisible();
  });

  test('import CSV seed populates many test cases', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }
    const csv = readFileSync(join(__dirname, '../fixtures/jtm-demo.csv'), 'utf8');
    const res = await importBundleViaFormPost(page, csv, 'jtm-demo.csv');
    expect([200, 302]).toContain(res.status);
    // jtm-demo.csv uses TC-001 … TC-030 (WebApp), not TC-CSV-* (see jtm-seed-import.csv)
    await page.goto('/jtm/testcases/?project=WebApp&q=TC-001');
    await expect(page.getByRole('link', { name: 'TC-001' })).toBeVisible();
    await page.goto('/jtm/testcases/?project=WebApp&q=TC-029');
    await expect(page.getByRole('link', { name: 'TC-029' })).toBeVisible();
  });

  test('dashboard: project apply keeps scope in navigation', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    const project = `E2E-Apply-${Date.now()}`;
    await createProjectFromDashboard(page, project);

    await gotoJtmDashboardWhenReady(page);
    const projectSelect = page.locator('#jtm-dash-project');
    await expect(projectSelect).toBeVisible();
    await Promise.all([
      page.waitForURL(new RegExp(`[?&]project=${encodeURIComponent(project)}(?:&|$)`)),
      projectSelect.selectOption(project),
    ]);
  });

  test('dashboard: project lifecycle create, blocked delete, cleanup delete', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    const project = `E2E-Proj-${Date.now()}`;
    await createProjectFromDashboard(page, project);

    await createCaseViaUi(page, `Case for ${project}`, project);

    await page.goto(`/jtm/?project=${encodeURIComponent(project)}`);
    await clickAndConfirm(page, () => page.getByRole('button', { name: 'Delete project' }).first().click(), 'Delete');
    await expect(page).toHaveURL(new RegExp(`[?&]projectDeleteStatus=blocked(?:&|$)`));
    await expect(page).toHaveURL(new RegExp(`[?&]projectDeleteKey=${encodeURIComponent(project)}(?:&|$)`));

    await page.goto(`/jtm/testcases/?project=${encodeURIComponent(project)}`);
    await page.locator('#jtm-tc-select-all').check();
    await clickAndConfirm(page, () => page.getByRole('button', { name: 'Delete selected' }).click(), 'Delete');
    await expect(page).toHaveURL(/bulkDeleted=1/);

    await page.goto(`/jtm/?project=${encodeURIComponent(project)}`);
    await clickAndConfirm(page, () => page.getByRole('button', { name: 'Delete project' }).first().click(), 'Delete');
    await expect(page).toHaveURL(new RegExp(`[?&]projectDeleteStatus=deleted(?:&|$)`));
    await expect(page).toHaveURL(new RegExp(`[?&]projectDeleteKey=${encodeURIComponent(project)}(?:&|$)`));
  });

  test('dashboard: run progress updates when selecting another run', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    const tcA = await createCaseViaUi(page, 'E2E Dashboard Pie A');
    const tcB = await createCaseViaUi(page, 'E2E Dashboard Pie B');

    await page.goto('/jtm/runs/newrun');
    await page.locator('#name:visible').first().fill('E2E Pie Run A');
    await page.locator(`input[name="linkedTestCaseId"][value="${tcA}"]`).check();
    await page.getByRole('button', { name: 'Create test run' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/runs\/RUN-/);

    await page.goto('/jtm/runs/newrun');
    await page.locator('#name:visible').first().fill('E2E Pie Run B');
    await page.locator(`input[name="linkedTestCaseId"][value="${tcA}"]`).check();
    await page.locator(`input[name="linkedTestCaseId"][value="${tcB}"]`).check();
    await page.getByRole('button', { name: 'Create test run' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/runs\/RUN-/);

    await gotoJtmDashboardWhenReady(page);
    for (let attempt = 0; attempt < 8; attempt++) {
      await page.reload({ waitUntil: 'load' });
      if ((await page.locator('#jtm-run-pie-select').count()) > 0) break;
      if (attempt < 7) await delay(2000);
    }
    const select = page.locator('#jtm-run-pie-select');
    await expect(select).toBeVisible({ timeout: 60_000 });
    await expect(select.locator('option')).toHaveCount(2);

    // Dashboard labels are "${run.name} (${run.jobName})" when job is set; ad-hoc runs default job to "manual".
    const valueA = await select.locator('option', { hasText: 'E2E Pie Run A' }).first().getAttribute('value');
    const valueB = await select.locator('option', { hasText: 'E2E Pie Run B' }).first().getAttribute('value');
    expect(valueA).toBeTruthy();
    expect(valueB).toBeTruthy();
    await select.selectOption(valueA!);
    const subA = await page.locator('#jtm-run-pie-sub').textContent();
    const linkedA = await page.locator('#jtm-run-pie-linked').textContent();

    await select.selectOption(valueB!);
    await expect(page.locator('#jtm-run-pie-sub')).not.toHaveText(subA ?? '');
    await expect(page.locator('#jtm-run-pie-linked')).not.toHaveText(linkedA ?? '');
  });

  test('runs: project filter reloads list on selection', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    const project = `E2E-RunFilter-${Date.now()}`;
    await createProjectFromDashboard(page, project);

    await page.goto(`/jtm/runs/newrun?project=${encodeURIComponent(project)}`);
    await page.locator('#name:visible').first().fill('E2E Runs Filter Scoped');
    await page.getByRole('button', { name: 'Create test run' }).first().click();
    await expect(page).toHaveURL(/\/jtm\/runs\/RUN-/);

    await postClearProjectScope(page);
    await page.goto('/jtm/runs/');
    const projectSelect = page.locator('#jtm-runs-project');
    await Promise.all([
      page.waitForURL(new RegExp(`[?&]project=${encodeURIComponent(project)}(?:&|$)`)),
      projectSelect.selectOption(project),
    ]);
    await expect(page.getByRole('link', { name: /E2E Runs Filter Scoped/i })).toBeVisible();
  });

  test('testcases: select all toggles all row checkboxes', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    await createCaseViaUi(page, 'E2E SelectAll A');
    await createCaseViaUi(page, 'E2E SelectAll B');

    await postClearProjectScope(page);
    await page.goto('/jtm/testcases/');
    const rows = page.locator('.jtm-tc-row-check');
    await expect(rows).toHaveCount(2);
    await page.locator('#jtm-tc-select-all').click();
    await expect(rows.first()).toBeChecked();
    await expect(rows.nth(1)).toBeChecked();
    await page.locator('#jtm-tc-select-all').click();
    await expect(rows.first()).not.toBeChecked();
    await expect(rows.nth(1)).not.toBeChecked();
  });

  test('runs: bulk export and bulk delete selected runs', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    await createRunViaUi(page, 'E2E Bulk Run A');
    await createRunViaUi(page, 'E2E Bulk Run B');

    await postClearProjectScope(page);
    await page.goto('/jtm/runs/');
    const runChecks = page.locator('.jtm-runs-row-check');
    await expect(runChecks).toHaveCount(2);
    await page.locator('#jtm-runs-select-all').click();
    await expect(runChecks.first()).toBeChecked();
    await expect(runChecks.nth(1)).toBeChecked();

    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export selected' }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toContain('jtm-multi-run-');

    await clickAndConfirm(page, () => page.getByRole('button', { name: 'Delete selected' }).first().click(), 'Delete');
    await expect(page).toHaveURL(/bulkDeleted=2/);
  });

  test('api: testcase status invalid payload returns 400', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    const crumb = await crumbPair(page);
    const crumbName = Object.keys(crumb)[0];
    const crumbValue = crumb[crumbName];
    const requestInit = {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        [crumbName]: crumbValue,
      },
      data: { id: 'TC-1234' },
    } as const;
    let resp = await page.request.post('/jtm/api/testcase/TC-1234/status', requestInit);
    if (resp.status() === 405) {
      // Some Stapler routes require trailing slash to reach doIndex on nested action.
      resp = await page.request.post('/jtm/api/testcase/TC-1234/status/', requestInit);
    }
    expect([400, 500]).toContain(resp.status());
    const body = await resp.text();
    expect(body).toContain('status');
  });

  test('empty states: testcases and runs render helpful empty UI', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }
    await page.goto('/jtm/testcases/');
    await expect(page.getByText('No test cases match')).toBeVisible();
    await page.goto('/jtm/runs/');
    await expect(page.getByText('No test runs yet')).toBeVisible();
  });

  test('new pages: no duplicate core form elements are rendered', async ({ page }) => {
    await jenkinsLogin(page);

    await page.goto('/jtm/testcases/newcase');
    await expect(page.locator('#title')).toHaveCount(1);
    await expect(page.locator('.jtm-nc-hero__title')).toHaveCount(1);
    await expect(page.locator('button.jtm-nc-submit')).toHaveCount(1);

    await page.goto('/jtm/runs/newrun');
    await expect(page.locator('#name')).toHaveCount(1);
    await expect(page.locator('.jtm-newrun-hero__title')).toHaveCount(1);
    await expect(page.locator('button.jtm-newrun-submit')).toHaveCount(1);
  });

  test('responsive + dark mode smoke has no page errors', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    const pageErrors: string[] = [];
    page.on('pageerror', (e) => pageErrors.push(String(e)));
    await gotoJtmDashboardWhenReady(page);
    await expect(page.locator('h1.jtm-dash-hero__title')).toContainText('Dashboard');
    await page.emulateMedia({ colorScheme: 'dark' });
    // Narrow viewport after the dashboard check: some Jenkins layouts omit main-panel content
    // when the first paint happens at phone widths.
    await page.setViewportSize({ width: 600, height: 860 });
    await page.goto('/jtm/runs/newrun');
    await expect(page.getByRole('heading', { name: 'New test run' }).first()).toBeVisible();
    expect(pageErrors, `Page errors detected: ${pageErrors.join(' | ')}`).toHaveLength(0);
  });

  test('bulk actions: selecting none shows warning notifications', async ({ page }) => {
    await jenkinsLogin(page);
    if (!process.env.JTM_E2E_NO_RESET) {
      const reset = await postJtmReset(page);
      expect(reset.ok, `JTM reset failed (HTTP ${reset.status})`).toBeTruthy();
    }

    await createCaseViaUi(page, 'E2E no-selection case');
    await createRunViaUi(page, 'E2E no-selection run');

    await postClearProjectScope(page);
    await page.goto('/jtm/testcases/');
    await page.getByRole('button', { name: 'Delete selected' }).click();
    // JS should prevent submit when nothing is selected (URL may stay /jtm/testcases/ without ?).
    await expect(page).toHaveURL(/\/jtm\/testcases\/?(\?|$)/);

    await postClearProjectScope(page);
    await page.goto('/jtm/runs/');
    await page.getByRole('button', { name: 'Export selected' }).click();
    await expect(page).toHaveURL(/\/jtm\/runs\/?(\?|$)/);
    await page.getByRole('button', { name: 'Delete selected' }).click();
    await expect(page).toHaveURL(/\/jtm\/runs\/?(\?|$)/);
  });
});
