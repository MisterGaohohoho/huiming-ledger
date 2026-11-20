'use strict';
const assert=require('node:assert/strict'),path=require('node:path'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
 try{for(const width of [320,360,393,430]){
  const page=await browser.newPage({viewport:{width,height:852},reducedMotion:'reduce'}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));await page.route('https://**',route=>route.abort());
  await page.addInitScript(()=>{
   const at=new Date(2026,10,18,12).getTime();window.lastQuery=null;window.lastSelection=null;
   const rows=Array.from({length:251},(_,i)=>({id:251-i,merchant:'测试账单 '+i,category:i%2?'工资':'餐饮',note:'',occurredAt:at-i*1000,direction:i%2?'INCOME':'EXPENSE',source:'MANUAL',state:'POSTED',amountCents:100,paymentProvider:'wechat'}));
   window.LedgerNative={remember(){},save(){return JSON.stringify({ok:true,id:9999})},unifiedSnapshot(text){const q=JSON.parse(text);lastQuery=q;
    const filtered=rows.filter(r=>!q.direction||r.direction===q.direction),offset=q.beforeId?filtered.findIndex(r=>r.id===q.beforeId)+1:0,part=filtered.slice(offset,offset+200);
    return JSON.stringify({ok:true,records:part,channels:{ocr:{enabled:false}},automatic:{applications:[]},accounts:[],recognitionTasks:[],dataRevision:'1',ledgerView:{records:part,hasMore:offset+200<filtered.length,nextCursor:{occurredAt:part.at(-1)?.occurredAt,id:part.at(-1)?.id},summary:{totals:{income:12500,expense:12600,incomeCount:125,expenseCount:126},days:[{date:'2026-11-18',day:18,direction:'INCOME',amount:12500},{date:'2026-11-18',day:18,direction:'EXPENSE',amount:12600}]}}});
   },previewBatchDelete(text){const q=JSON.parse(text);lastSelection=q;const chosen=rows.filter(r=>(!q.direction||r.direction===q.direction)&&(q.all?!q.excluded.includes(r.id):q.ids.includes(r.id)));return JSON.stringify({ok:true,token:'preview',count:chosen.length,income:chosen.filter(r=>r.direction==='INCOME').length*100,expense:chosen.filter(r=>r.direction==='EXPENSE').length*100,reviewCount:0})}};
  });
  await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/web/index.html')).href);
  await page.evaluate(()=>{month='2026-11-01..2026-11-18';LedgerScope.channel='wechat';refresh()});
  await page.locator('[data-filter="all"]').click();
  assert.equal(await page.evaluate(()=>lastQuery.direction),'');
  assert.equal(await page.evaluate(()=>lastQuery.channel),'wechat');
  assert.equal(await page.locator('[data-record]').count(),200);
  assert.match(await page.locator('#home-count').textContent(),/251/);
  assert.match(await page.locator('#home-combined').textContent(),/收入¥ 125支出¥ 126/);
  assert.match(await page.locator('.day-totals').first().textContent(),/收 ¥ 125支 ¥ 126/);
  for(const id of [251,250]){await page.locator(`[data-record="${id}"]`).click();assert.equal(await page.evaluate(()=>filter),'all','Opening either direction must keep combined mode');await page.evaluate(()=>closeRecordPanel(false));assert.equal(await page.evaluate(()=>month),'2026-11-01..2026-11-18')}
  await page.locator('#load-more-records').click();assert.equal(await page.locator('[data-record]').count(),251);
  await page.locator('[data-record]').first().click({button:'right'});await page.locator('#selection-all').click();
  assert.equal(await page.evaluate(()=>lastSelection.direction),'');assert.match(await page.locator('#selection-count').textContent(),/251/);
  await page.locator('#selection-delete').click();assert.match(await page.locator('.batch-scope').textContent(),/收支/);
  await page.locator('#batch-cancel').click();await page.locator('#selection-cancel').click();
  await page.evaluate(()=>{month='all';beginManual();$('manual-amount').value='1';$('manual-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))});
  assert.equal(await page.evaluate(()=>filter),'all','Manual save must retain combined mode');assert.equal(await page.evaluate(()=>month),'all','Manual save inside the scope must retain its dates');
  await page.evaluate(()=>{month='2026-11';LedgerScope.channel='';refresh()});
  for(const large of [false,true]){
   await page.evaluate(large=>{LedgerUI.event('fontScale',large?1.5:1);ledgerView.summary.totals.income=98765432100;ledgerView.summary.totals.expense=12345678900;render();document.querySelector('#ledger').scrollTop=0;if(large){const sizes=[...document.querySelectorAll('#ledger *')].map(e=>[e,parseFloat(getComputedStyle(e).fontSize)]);sizes.forEach(([e,size])=>e.style.fontSize=(size*1.5)+'px')}},large);
   const metrics=await page.evaluate(()=>({overflow:document.documentElement.scrollWidth>innerWidth,controls:[...document.querySelectorAll('#month,.filter,.balance,#home-combined dd,#home-count,.summary-trend')].map(e=>{const r=e.getBoundingClientRect();return {left:r.left,right:r.right,width:r.width,scroll:e.scrollWidth,client:e.clientWidth}}),footer:['#home-count','.summary-trend','#monthly-budget'].map(selector=>{const r=document.querySelector(selector).getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,center:r.top+r.height/2}})}));
   assert.equal(metrics.overflow,false);assert(metrics.controls.every(r=>r.left>=0&&r.right<=width&&r.width>0&&r.scroll<=r.client+1),JSON.stringify(metrics));
   const [count,trend,budget]=metrics.footer,sameRow=Math.abs(count.center-trend.center)<1;
   if(!large)assert(sameRow,'Count and trend must share one aligned row: '+JSON.stringify(metrics.footer));
   assert((sameRow&&count.right+6<=trend.left)||trend.top>=count.bottom,'Footer text must not overlap: '+JSON.stringify(metrics.footer));
   assert(budget.top>=Math.max(count.bottom,trend.bottom),'Budget must remain below the footer: '+JSON.stringify(metrics.footer));
   if(process.env.UI_SCREENSHOTS)await page.screenshot({path:path.join(process.env.UI_SCREENSHOTS,`home-all-${width}-${large?'large':'normal'}.png`)});
  }
  await page.locator('[data-filter="income"]').click();assert.equal(await page.evaluate(()=>lastQuery.direction),'INCOME');assert.equal(await page.locator('#home-combined').isVisible(),false);
  assert.deepEqual(errors,[]);console.log(`PASS home ${width}: both directions, full totals, 251-row pagination/selection, dates, large text and amounts`);await page.close();
 }}finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1});
