'use strict';
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{for(const width of [320,393,430]){
  const page=await browser.newPage({viewport:{width,height:852},reducedMotion:'reduce'}),errors=[],network=[];
  page.on('pageerror',e=>errors.push(e.message));await page.route('https://**',r=>{network.push(r.request().url());return r.abort()});
  await page.addInitScript(()=>{
   const now=Date.now(),at=new Date(now),month=`${at.getFullYear()}-${String(at.getMonth()+1).padStart(2,'0')}`;
   const records=Array.from({length:55},(_,i)=>({id:i+1,merchant:i===0?'测试商户 <script>':'微信商户 '+i,category:'餐饮',note:'',occurredAt:now-i*1000,amountCents:1000,direction:'EXPENSE',source:'MANUAL',state:'POSTED',channel:'wechat'}));
   records.push({id:100,merchant:'支付宝商户',category:'工资',note:'',occurredAt:now,amountCents:10000,direction:'INCOME',source:'MANUAL',state:'POSTED',channel:'alipay'});
   window.offlineCalls=[];window.importChannel='';window.failOffline=false;
   const select=q=>records.filter(r=>(!q.channel||r.channel===q.channel)&&(!q.direction||r.direction===q.direction)&&(!q.category||r.category===q.category)&&(!q.search||r.merchant.includes(q.search)));
   window.LedgerNative={remember(){},snapshotView(){return JSON.stringify({ok:true,records,channels:{ocr:{enabled:false}},accounts:[],automatic:{applications:[]}})},
    offlineSummary(json){const q=JSON.parse(json);offlineCalls.push({kind:'summary',...q});if(failOffline)return JSON.stringify({ok:false,error:'模拟读取失败'});const rs=select(q),income=rs.filter(r=>r.direction==='INCOME'),expense=rs.filter(r=>r.direction==='EXPENSE');return JSON.stringify({ok:true,revision:'1',budgetCents:100000,totals:{income:income.length*10000,expense:expense.length*1000,incomeCount:income.length,expenseCount:expense.length,balance:income.length*10000-expense.length*1000},previous:{expense:10000},categories:[...expense.length?[{name:'餐饮',amount:expense.length*1000,count:expense.length,direction:'EXPENSE'}]:[],...income.length?[{name:'工资',amount:income.length*10000,count:income.length,direction:'INCOME'}]:[]],trend:Array.from({length:q.mode==='year'?12:30},(_,i)=>({index:i+1,income:i===0?10000:0,expense:1000})),top:expense.slice(0,10)})},
    offlineRecords(json){const q=JSON.parse(json);offlineCalls.push({kind:'records',...q});const rs=select(q);return JSON.stringify({ok:true,revision:'1',records:rs.slice(q.offset,q.offset+q.limit),hasMore:q.offset+q.limit<rs.length})},
    importPlatformBills(channel){window.importChannel=channel},recordDetails(id){return JSON.stringify({ok:true,record:records.find(r=>r.id===id),history:[],rawText:''})}
   };
  });
  await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/web/index.html')).href);
  await page.locator('[data-open-channel="wechat"]').click();
  assert.equal(await page.locator('#channel-title').textContent(),'微信账单');assert.equal(await page.locator('#channel-records button').count(),50);
  await page.locator('#channel-more').click();assert.equal(await page.locator('#channel-records button').count(),55);
  assert.equal(await page.locator('#channel-more').isVisible(),false);
  await page.locator('#channel-search').fill('微信商户 2');await page.waitForFunction(()=>offlineCalls.some(q=>q.search==='微信商户 2'));
  assert.equal(await page.locator('#channel-records button').count(),11);
  await page.locator('#channel-import').click();assert.equal(await page.evaluate(()=>importChannel),'wechat');
  await page.locator('#channel-switch').selectOption('alipay');assert.equal(await page.locator('#channel-records button').count(),1);
  await page.locator('#channel-import').click();assert.equal(await page.evaluate(()=>importChannel),'alipay');
  await page.locator('#channel-records button').first().click();assert.equal(await page.evaluate(()=>recordPanelOpen),true);
  await page.evaluate(()=>LedgerUI.back());await page.waitForFunction(()=>!recordPanelOpen&&!recordPanelClosing);
  assert.equal(await page.evaluate(()=>page),'channel-ledger');
  await page.locator('#channel-report').click();assert.equal(await page.locator('#analysis-channel').inputValue(),'alipay');
  assert.equal(await page.locator('#offline-analysis canvas').count(),2);
  await page.locator('#analysis-mode').selectOption('year');assert.equal(await page.locator('#analysis-year').isVisible(),true);assert.equal(await page.locator('#analysis-month').isVisible(),false);
  await page.locator('#analysis-channel').selectOption('');await page.locator('#analysis-direction').selectOption('INCOME');
  assert.match(await page.locator('#analysis-ranking').textContent(),/工资/);
  await page.locator('#analysis-ranking button').first().click();assert.equal(await page.locator('#sheet-content [data-offline-record]').count(),1);await page.evaluate(()=>LedgerUI.back());
  await page.locator('#analysis-reimbursement').selectOption('required');assert.equal(await page.evaluate(()=>offlineCalls.filter(q=>q.kind==='summary').at(-1).reimbursement),'required');
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  fs.mkdirSync(path.resolve(__dirname,'../.tools/ui-qa'),{recursive:true});await page.locator('#offline-analysis').evaluate(e=>e.scrollTop=0);await page.screenshot({path:path.resolve(__dirname,`../.tools/ui-qa/analysis-${width}.png`),fullPage:true});
  await page.evaluate(()=>LedgerUI.event('fontScale',1.5));assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await page.evaluate(()=>{failOffline=true;OfflineBills.refresh()});assert.match(await page.locator('#analysis-status').textContent(),/模拟读取失败/);assert.equal(await page.locator('#analysis-totals .offline-total').count(),0);
  await page.evaluate(()=>{failOffline=false;OfflineBills.refresh()});await page.evaluate(()=>LedgerUI.back());assert.equal(await page.evaluate(()=>page),'channel-ledger');
  assert.deepEqual(network,[],'No remote chart assets or APIs');assert.deepEqual(errors,[]);console.log(`PASS ${width}: offline charts, channel management, paging, imports, details, filters, errors and font scaling`);await page.close();
 }}finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
