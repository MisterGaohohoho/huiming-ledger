'use strict';
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
 try{for(const width of [320,393,430]){
  const page=await browser.newPage({viewport:{width,height:852},reducedMotion:'reduce'}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));await page.route('https://**',r=>r.abort());
  await page.addInitScript(()=>{
   const now=Date.now(),uid='12345678-1234-1234-1234-123456789abc';
   window.mockBatch={uid,fileName:'合成微信账单 <img src=x onerror=alert(1)>.xlsx',source:'微信',importedAt:now,from:now-86400000,through:now,created:251,review:1,updated:2,duplicates:8,invalid:0,remaining:251};
   window.storageState={bytes:550*1048576,maxBytes:500*1048576,protectedBytes:0,count:251};
   window.previewCalls=[];window.deletion=null;window.failStorage=false;
   window.LedgerNative={
    unifiedSnapshot(){return JSON.stringify({ok:true,records:[],channels:{ocr:{enabled:false}},accounts:[],automatic:{applications:[]},pendingDeletion:deletion,screenshotStorage:storageState,ledgerView:{hasMore:false,summary:{}}})},
    importHistory(offset){return JSON.stringify({ok:true,items:offset?[]:[mockBatch],hasMore:false})},
    importBatchDetail(id,offset){return JSON.stringify({ok:true,batch:mockBatch,hasMore:offset+100<mockBatch.remaining,rows:Array.from({length:Math.min(100,mockBatch.remaining-offset)},(_,i)=>({id:offset+i+1,merchant:'合成长名称商户 '+i,at:now,amount:100,direction:'EXPENSE',state:i===0?'REVIEW':'POSTED'}))})},
    previewBatchDelete(request){previewCalls.push(JSON.parse(request));return JSON.stringify({ok:true,token:'batch-plan',batch:mockBatch,count:251,income:0,expense:25100,reviewCount:1,editedCount:2})},
    deleteBatch(){mockBatch.remaining=0;deletion={ok:true,id:1,batchId:uid,count:251,expiresAt:now+60000};return JSON.stringify(deletion)},
    undoDeleteBatch(){mockBatch.remaining=251;deletion=null;return '{"ok":true}'},
    screenshotStorageInfo(){return JSON.stringify({...storageState,ok:true})},
    previewScreenshotLimit(n){window.requestedLimit=n;return JSON.stringify({...storageState,ok:true,token:'storage-plan',proposedBytes:n*1048576,removeCount:n<550?10:0,removeBytes:Math.max(0,550-n)*1048576,projectedBytes:Math.min(550,n)*1048576})},
    applyScreenshotLimit(){if(failStorage){failStorage=false;return JSON.stringify({ok:false,error:'截图状态已变化，请重新核对清理范围'})}const before=storageState.bytes;storageState.bytes=Math.min(before,requestedLimit*1048576);storageState.maxBytes=requestedLimit*1048576;return JSON.stringify({ok:true,storage:{...storageState,freedBytes:before-storageState.bytes}})},
    remember(){}
   };
  });
  await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/web/index.html')).href);
  await page.evaluate(()=>document.fonts.ready);await page.evaluate(()=>go('settings'));
  await page.locator('#backup').click();await page.locator('#import-history').click();
  assert.match(await page.locator('#import-history-list').textContent(),/251/);assert.equal(await page.locator('#import-history-list img').count(),0);
  await page.locator('[data-import-batch]').click();assert.equal(await page.locator('.management-bill').count(),100);
  await page.locator('#import-batch-more').click();await page.locator('#import-batch-more').click();assert.equal(await page.locator('.management-bill').count(),251);
  await page.locator('#import-batch-delete').click();assert.match(await page.locator('#sheet-title').textContent(),/251/);assert.match(await page.locator('#sheet-content').textContent(),/2 笔导入后有修改/);
  assert.equal(await page.evaluate(()=>previewCalls[0].all),true);
  await page.locator('#import-delete-cancel').click();assert.equal(await page.evaluate(()=>mockBatch.remaining),251);
  await page.locator('#import-batch-delete').click();await page.locator('#import-delete-confirm').click();
  await page.getByRole('button',{name:'整批撤销',exact:true}).click();assert.equal(await page.evaluate(()=>mockBatch.remaining),251);
  await page.locator('#screenshot-storage').click();await page.locator('#screenshot-limit').fill('49');await page.locator('#storage-save').click();
  assert.equal(await page.locator('#screenshot-limit').isVisible(),true);
  await page.locator('[data-storage-preset="300"]').click();await page.locator('#storage-save').click();
  assert.match(await page.locator('#sheet-content').textContent(),/250 MiB/);await page.locator('#storage-cancel').click();assert.equal(await page.evaluate(()=>storageState.maxBytes),500*1048576);
  await page.locator('#screenshot-limit').fill('1024');await page.locator('#storage-save').click();await page.locator('#storage-confirm').click();
  assert.equal(await page.locator('#screenshot-limit').inputValue(),'1024');assert.match(await page.locator('#screenshot-storage-summary').textContent(),/1,024/);
  await page.evaluate(()=>failStorage=true);await page.locator('#storage-clean').click();await page.locator('#storage-confirm').click();
  assert.equal(await page.locator('#screenshot-limit').isVisible(),true);assert.match(await page.locator('.toast').textContent(),/状态已变化/);
  await page.evaluate(()=>LedgerUI.event('fontScale',1.5));
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  if(process.env.UI_SCREENSHOTS){fs.mkdirSync(process.env.UI_SCREENSHOTS,{recursive:true});await page.screenshot({path:path.join(process.env.UI_SCREENSHOTS,`storage-${width}.png`)});}
  await page.evaluate(()=>{closeSheet();DataManagement.history()});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  if(process.env.UI_SCREENSHOTS)await page.screenshot({path:path.join(process.env.UI_SCREENSHOTS,`imports-${width}.png`)});
  assert.deepEqual(errors,[]);console.log(`PASS ${width}: import history, pagination, confirm/cancel/undo, escaped filenames, capacity validation/preview/persistence, stale plan, large text`);
  await page.close();
 }}finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
