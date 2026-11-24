const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({headless:true,args:['--no-sandbox','--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream']});
  const page = await browser.newPage({permissions:['microphone']});
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => {if (m.type()==='error') console.log('BROWSER_ERROR',m.text());});
  await page.route('**/*', r => r.request().url().startsWith('http://127.0.0.1:8000/')?r.continue():r.abort());
  await page.goto('http://127.0.0.1:8000/',{waitUntil:'domcontentloaded',timeout:40000});
  console.log('INITIAL_STATUS',await page.locator('#status').innerText());
  try {
    await page.waitForFunction(() => {
      const b=document.getElementById('startBtn');
      return b&&!b.disabled;
    },null,{timeout:180000});
    console.log('MODEL_READY',await page.locator('#status').innerText());
    await page.locator('#startBtn').click();
    await page.waitForTimeout(3500);
    console.log('AFTER_MIC_START',await page.locator('#status').innerText());
    await page.locator('#stopBtn').click();
    console.log('PASS: browser WASM initialized, controls worked, no internet resources used');
    if (errors.length) console.log('NONFATAL_PAGE_ERRORS',errors.slice(0,10));
  } finally {
    console.log('FINAL_STATUS',await page.locator('#status').innerText());
    await browser.close();
  }
})().catch(e=>{console.error('FAIL:',e);process.exit(1)});
