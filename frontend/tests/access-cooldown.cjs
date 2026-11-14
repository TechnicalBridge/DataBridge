// Requires Playwright (or PLAYWRIGHT_MODULE pointing to playwright-core) and a local Vite server.
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
(async () => {
  const browser = await chromium.launch({headless:true});
  const base = process.env.PORTAL_TEST_URL || "http://127.0.0.1:5308";
  const output = process.env.PORTAL_TEST_OUTPUT || "test-results/cooldown";
  await fs.mkdir(output, {recursive:true});
  try {
    for (const portal of ["/login", "/databridge/login", "/admin/login"]) {
      for (const width of [390,1360]) for (const theme of ["light","dark"]) {
        const context = await browser.newContext({viewport:{width,height:900},colorScheme:theme});
        const page = await context.newPage();
        page.setDefaultTimeout(15000);
        console.log(`START ${portal} ${width} ${theme}`);
        let requests = 0;
        await page.route("**/*", async route => {
          const url = new URL(route.request().url());
          if (url.origin !== base) return route.abort();
          if (!url.pathname.startsWith("/api/")) return route.continue();
          if (["/api/auth/codigo","/api/auth/enlace"].includes(url.pathname)) {
            requests++;
            return route.fulfill({json:{ok:true,mensaje:"Solicitud aceptada.",expiraEnMinutos:15}});
          }
          return route.fulfill({status:401,json:{error:"fixture"}});
        });
        await page.goto(base + portal, {waitUntil:"domcontentloaded", timeout:30000});
        console.log("page loaded");
        await page.clock.install();
        await page.locator("#identificador").fill(portal === "/login" ? "16482337-7" : "fixture@example.com");
        // Two clicks in the same JS task must produce one HTTP request, even before React re-renders.
        await page.getByRole("button",{name:"Enviar código",exact:true}).evaluate(button => {button.click();button.click();});
        const resend = page.getByRole("button",{name:/Reenviar código en/});
        await resend.waitFor();
        assert.equal(requests,1); assert.equal(await resend.isDisabled(),true);
        assert.match(await resend.innerText(),/60 s/);
        assert.equal(await page.getByText(/Revisa también tu carpeta de spam/).isVisible(),true);
        assert.equal(await page.getByRole("button",{name:"¿No te llegó?"}).isDisabled(),true);
        await page.clock.runFor(1000); assert.match(await resend.innerText(),/59 s/);
        const data = await page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage)));
        assert.ok(Object.keys(data).every(key => key.startsWith("databridge:access-cooldown:")));
        assert.ok(Object.values(data).every(value => /^\d+$/.test(value)));
        await page.screenshot({path:path.join(output,`${portal.replaceAll("/","_")}-${width}-${theme}.png`),fullPage:true});
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
        await page.reload();
        await page.getByRole("button",{name:/Reenviar código en/}).waitFor();
        assert.equal(await page.getByRole("button",{name:/Reenviar código en/}).isDisabled(),true);
        await page.clock.runFor(61000);
        const enabled = page.getByRole("button",{name:"Reenviar código",exact:true});
        assert.equal(await enabled.isEnabled(),true);
        assert.equal(requests,1);
        console.log(`PASS portal=${portal} width=${width} theme=${theme} countdown double_click reload storage`);
        await context.close();
      }
    }
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
