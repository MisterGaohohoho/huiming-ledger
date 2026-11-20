'use strict';
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{for(const width of [320,393,430]){
  const page=await browser.newPage({viewport:{width,height:852},reducedMotion:'reduce',timezoneId:'Asia/Shanghai'}),errors=[],network=[];
  await page.clock.setFixedTime(new Date('2026-11-20T00:30:00+08:00'));
  page.on('pageerror',e=>errors.push(e.message));await page.route('https://**',r=>{network.push(r.request().url());return r.abort()});
  await page.addInitScript(()=>{
   const now=Date.now(),at=new Date(now),month=`${at.getFullYear()}-${String(at.getMonth()+1).padStart(2,'0')}`;
   const records=Array.from({length:55},(_,i)=>({id:i+1,merchant:i===0?'测试商户 <script>':'微信商户 '+i,category:'餐饮',note:'',occurredAt:now-i*1000,amountCents:1000,direction:'EXPENSE',source:'MANUAL',state:'POSTED',channel:'wechat',paymentProvider:'wechat',importLabel:'洁账导入',tags:[],requiresReimbursement:false}));
   records.push({id:100,merchant:'支付宝商户',category:'工资',note:'',occurredAt:now,amountCents:10000,direction:'INCOME',source:'MANUAL',state:'POSTED',channel:'alipay',paymentProvider:'alipay',importLabel:'账单导入',tags:[],requiresReimbursement:false});
   window.offlineCalls=[];window.importChannel='';window.failOffline=false;window.unifiedImport=false;window.snapshotCalls=[];
   const select=q=>records.filter(r=>(!q.channel||r.channel===q.channel)&&(!q.direction||r.direction===q.direction)&&(!q.category||r.category===q.category)&&(!q.search||r.merchant.includes(q.search)));
   window.LedgerNative={billLabels(){return JSON.stringify({ok:true,tags:[]})},unifiedSnapshot(json){const q=JSON.parse(json);snapshotCalls.push(q);const rs=select(q),offset=q.beforeId?rs.findIndex(r=>r.id===q.beforeId)+1:0,rows=rs.slice(offset,offset+20),all=select({...q,direction:''});return JSON.stringify({ok:true,dataRevision:'1',records:rows,monthlyBudgetCents:100000,channels:{ocr:{enabled:false}},accounts:[],automatic:{applications:[]},ledgerView:{hasMore:offset+20<rs.length,nextCursor:rows.length?{id:rows.at(-1).id,occurredAt:rows.at(-1).occurredAt}:null,summary:{totals:{income:all.filter(r=>r.direction==='INCOME').length*10000,expense:all.filter(r=>r.direction==='EXPENSE').length*1000},days:[],previous:{}}}})},importBills(){window.unifiedImport=true},remember(){},snapshotView(){return JSON.stringify({ok:true,records,channels:{ocr:{enabled:false}},accounts:[],automatic:{applications:[]}})},
    offlineSummary(json){const q=JSON.parse(json);offlineCalls.push({kind:'summary',...q});if(failOffline)return JSON.stringify({ok:false,error:'模拟读取失败'});const rs=select(q),income=rs.filter(r=>r.direction==='INCOME'),expense=rs.filter(r=>r.direction==='EXPENSE');return JSON.stringify({ok:true,revision:'1',budgetCents:100000,totals:{income:income.length*10000,expense:expense.length*1000,incomeCount:income.length,expenseCount:expense.length,balance:income.length*10000-expense.length*1000},comparable:q.mode==='month'||q.mode==='year',trendUnit:q.mode==='month'?'day':'month',previous:{expense:10000},categories:[...expense.length?[{name:'餐饮',amount:expense.length*1000,count:expense.length,direction:'EXPENSE'}]:[],...income.length?[{name:'工资',amount:income.length*10000,count:income.length,direction:'INCOME'}]:[]],trend:Array.from({length:q.mode==='year'?12:30},(_,i)=>({index:i+1,label:String(i+1),period:'2026-01-01..2026-01-31',income:i===0?10000:0,expense:1000})),top:expense.slice(0,10)})},
    offlineRecords(json){const q=JSON.parse(json);offlineCalls.push({kind:'records',...q});const rs=select(q);return JSON.stringify({ok:true,revision:'1',records:rs.slice(q.offset,q.offset+q.limit),hasMore:q.offset+q.limit<rs.length})},
    importPlatformBills(channel){window.importChannel=channel},recordDetails(id){return JSON.stringify({ok:true,record:records.find(r=>r.id===id),history:[],rawText:''})}
   };
  });
  await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/web/index.html')).href);
  await page.locator('#month').click();await page.locator('[data-period-mode="custom"]').click();
  assert.equal(await page.locator('#period-from').inputValue(),'2026-11-20');assert.equal(await page.locator('#period-through').inputValue(),'2026-11-20');
  await page.locator('#period-cancel').click();assert.equal(await page.evaluate(()=>month),'2026-11','Canceling the date chooser must retain the current scope');
  assert.equal(await page.locator('[data-open-channel],#open-offline-analysis').count(),0);
  assert.equal(await page.evaluate(()=>records.length),20);await page.locator('#load-more-records').click();assert.equal(await page.evaluate(()=>records.length),40);assert.equal(await page.evaluate(()=>snapshotCalls.at(-1).beforeId),20);
  await page.evaluate(()=>openRecord(1));assert.match(await page.locator('#record-provenance').textContent(),/微信支付 · 洁账导入/);
  await page.evaluate(()=>{document.querySelector('#reimbursement-yes').click();document.querySelector('#bill-labels-button').click()});await page.locator('#new-label').fill('差旅');await page.locator('#new-label-form').evaluate(e=>e.requestSubmit());await page.locator('#labels-apply').click();
  await page.evaluate(()=>{document.querySelector('#reimbursement-yes').click();const state=getState();cancelEditor(true);restoreState(state);refresh()});
  assert.match(await page.locator('#bill-labels-value').textContent(),/差旅/);assert.equal(await page.locator('#reimbursement-yes').getAttribute('aria-pressed'),'true');await page.evaluate(()=>cancelEditor(true));

  await page.evaluate(()=>{go('settings');showDataTools()});await page.locator('#import-bills').click();assert.equal(await page.evaluate(()=>unifiedImport),true);
  await page.evaluate(()=>go('stats'));
  assert.equal(await page.locator('#integrated-trend canvas').count(),1);
  await page.locator('#stats .month').click();await page.locator('[data-period-mode="year"]').click();await page.locator('#period-year').fill('2026');await page.locator('#period-apply').click();
  assert.equal(await page.evaluate(()=>month),'2026');assert.match(await page.locator('#stats-month').textContent(),/2026年/);
  await page.locator('#stats-filter').click();assert.equal(await page.locator('#scope-channel option').count(),4);assert.doesNotMatch(await page.locator('#sheet-content').textContent(),/洁账/);
  await page.locator('#scope-channel').selectOption('alipay');await page.locator('#scope-apply').click();
  assert.match(await page.locator('#stats-scope-note').textContent(),/支付宝/);assert.equal(await page.evaluate(()=>snapshotCalls.at(-1).channel),'alipay');assert.equal(await page.evaluate(()=>snapshotCalls.at(-1).period),'2026');
  await page.locator('#stats-type [data-type="income"]').click();assert.match(await page.locator('#ranking').textContent(),/工资/);
  await page.locator('#ranking button').first().click();assert.equal(await page.locator('[data-unified-record]').count(),1);await page.locator('#sheet-content [data-unified-record]').first().click();
  assert.equal(await page.evaluate(()=>recordPanelOpen),true);await page.evaluate(()=>LedgerUI.back());await page.waitForFunction(()=>!recordPanelOpen&&!recordPanelClosing);
  assert.equal(await page.evaluate(()=>page),'stats');
  await page.locator('#stats-scope-note button').click();await page.locator('#stats-type [data-type="all"]').click();
  assert.equal(await page.locator('#integrated-totals').isVisible(),true);assert.equal(await page.locator('#analysis-total').isVisible(),false);
  await page.locator('#stats .month').click();await page.locator('[data-period-mode="all"]').click();await page.locator('#period-apply').click();assert.equal(await page.evaluate(()=>month),'all');
  assert.equal(await page.locator('#stats-change').textContent(),'');
  await page.locator('#stats .month').click();await page.locator('[data-period-mode="custom"]').click();
  assert.equal(await page.locator('#period-from').inputValue(),'2026-11-20');assert.equal(await page.locator('#period-through').inputValue(),'2026-11-20');
  await page.locator('#period-apply').click();assert.equal(await page.evaluate(()=>snapshotCalls.at(-1).period),'2026-11-20..2026-11-20');
  await page.locator('#stats .month').click();await page.locator('#period-from').fill('2026-01-01');await page.locator('#period-through').fill('2025-12-01');await page.locator('#period-apply').click();assert.match(await page.locator('#period-error').textContent(),/早于/);
  await page.locator('#period-through').fill('2026-12-31');await page.locator('#period-apply').click();assert.equal(await page.evaluate(()=>month),'2026-01-01..2026-12-31');
  await page.locator('#stats .month').click();assert.equal(await page.locator('#period-from').inputValue(),'2026-01-01');assert.equal(await page.locator('#period-through').inputValue(),'2026-12-31');await page.locator('#period-cancel').click();
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  fs.mkdirSync(path.resolve(__dirname,'../.tools/ui-qa'),{recursive:true});await page.locator('#stats').evaluate(e=>e.scrollTop=0);await page.screenshot({path:path.resolve(__dirname,`../.tools/ui-qa/youshu-stats-${width}.png`),fullPage:true});
  await page.evaluate(()=>LedgerUI.event('fontScale',1.5));assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await page.locator('#stats .month').click();assert.equal(await page.locator('#period-fields').evaluate(e=>e.scrollWidth<=e.clientWidth),true);await page.evaluate(()=>LedgerUI.back());
  await page.evaluate(()=>{failOffline=true;drawStats()});assert.match(await page.locator('#stats-error').textContent(),/模拟读取失败/);assert.equal(await page.locator('.analysis-card').isVisible(),false);
  await page.evaluate(()=>{failOffline=false;drawStats();go('ledger')});assert.equal(await page.locator('#month h1').textContent(),'2026-01-01 至 2026-12-31');assert.equal(await page.locator('#monthly-budget').isVisible(),false);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await page.screenshot({path:path.resolve(__dirname,`../.tools/ui-qa/youshu-ledger-${width}.png`),fullPage:true});
  assert.deepEqual(network,[],'No remote chart assets or APIs');assert.deepEqual(errors,[]);console.log(`PASS ${width}: integrated statistics, month/year/all/custom, unified import, payment filters, details, error recovery and font scaling`);await page.close();
 }}finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
