'use strict';
const assert=require('node:assert/strict'),path=require('node:path'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})});
 try{for(const width of [320,393,430]){
  const page=await browser.newPage({viewport:{width,height:852},reducedMotion:'reduce'}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));await page.route('https://**',r=>r.abort());
  await page.addInitScript(()=>{
   const now=Date.now();window.mockRows=Array.from({length:251},(_,i)=>({id:i+1,merchant:'合成账单 '+(i+1),category:'餐饮',note:'',occurredAt:now-i*1000,direction:'EXPENSE',source:'MANUAL',state:i===1?'REVIEW':'POSTED',amountCents:100,paymentProvider:'wechat'}));
   window.deletion=null;window.deletedRows=[];window.pickCount=0;window.previewRequests=[];let plan;
   const rows=()=>mockRows.slice(0,200);
   window.LedgerNative={
    unifiedSnapshot(){return JSON.stringify({ok:true,records:rows(),recognitionTasks:[{id:999,source:'OCR',origin:'shake:com.tencent.mm',createdAt:now,status:'NO_BILL',message:'未识别到账单'}],channels:{ocr:{enabled:false}},accounts:[],automatic:{applications:[]},pendingDeletion:deletion,dataRevision:String(mockRows.length),ledgerView:{records:rows(),hasMore:mockRows.length>200,summary:{},nextCursor:{occurredAt:1,id:200}}})},
    previewBatchDelete(json){const q=JSON.parse(json);previewRequests.push(q);const chosen=mockRows.filter(r=>q.all?!q.excluded.includes(r.id):q.ids.includes(r.id));plan=chosen;return JSON.stringify({ok:true,token:'preview',count:chosen.length,income:0,expense:chosen.length*100,reviewCount:chosen.filter(r=>r.state==='REVIEW').length})},
    deleteBatch(){deletedRows=plan;mockRows=mockRows.filter(r=>!plan.includes(r));deletion={ok:true,id:plan[0].id,batchId:'batch',count:plan.length,expiresAt:Date.now()+10000};return JSON.stringify(deletion)},
    undoDeleteBatch(){mockRows.push(...deletedRows);mockRows.sort((a,b)=>a.id-b.id);deletedRows=[];deletion=null;return '{"ok":true}'},
    importImage(){pickCount++},remember(){}
   };
  });
  await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/web/index.html')).href);
  await page.evaluate(()=>document.fonts.ready);
  assert.equal(await page.locator('[data-task="999"]').count(),0,'Invalid attempts must not appear on home');
  assert.equal(await page.locator('#selection-bar').isVisible(),false);
  const first=page.locator('[data-record="1"]');await first.scrollIntoViewIfNeeded();
  let rect=await first.boundingBox();await page.mouse.move(rect.x+rect.width/2,rect.y+rect.height/2);await page.mouse.down();await page.waitForTimeout(600);await page.mouse.up();
  assert.equal(await page.locator('#selection-count').textContent(),'已选 1 笔');
  assert.equal(await page.evaluate(()=>recordPanelOpen),false,'Long press must not open the editor');
  await page.waitForTimeout(1000);await page.locator('[data-pending="2"]').click();
  assert.equal(await page.locator('#selection-count').textContent(),'已选 2 笔');
  await page.locator('#selection-all').click();
  assert.equal(await page.locator('#selection-count').textContent(),'已选 251 笔','Select all must include unloaded rows');
  await first.click();assert.equal(await page.locator('#selection-count').textContent(),'已选 250 笔');
  await page.locator('#selection-delete').click();
  assert.match(await page.locator('#sheet-title').textContent(),/250/);assert.match(await page.locator('#sheet-content').textContent(),/¥ 250/);
  await page.locator('#batch-cancel').click();assert.equal(await page.locator('#selection-bar').isVisible(),true);
  await page.locator('#selection-delete').click();await page.locator('#batch-confirm').click();
  assert.equal(await page.evaluate(()=>mockRows.length),1);
  await page.getByRole('button',{name:'整批撤销',exact:true}).click();
  assert.equal(await page.evaluate(()=>mockRows.length),251);
  await page.locator('[data-record="1"]').click({button:'right'});
  await page.evaluate(()=>LedgerUI.event('fontScale',1.5));
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  const buttons=await page.locator('#selection-bar button').evaluateAll(list=>list.map(e=>({x:e.getBoundingClientRect().left,right:e.getBoundingClientRect().right,width:e.getBoundingClientRect().width})));
  assert(buttons.every(b=>b.x>=0&&b.right<=width&&b.width>50));
  if(process.env.UI_SCREENSHOTS){await page.screenshot({path:path.join(process.env.UI_SCREENSHOTS,`selection-${width}.png`)});}
  await page.evaluate(()=>LedgerUI.back());assert.equal(await page.locator('#selection-bar').isVisible(),false);
  await page.evaluate(()=>beginManual());const card=page.locator('.entry-import');await card.scrollIntoViewIfNeeded();
  if(process.env.UI_SCREENSHOTS){await page.screenshot({path:path.join(process.env.UI_SCREENSHOTS,`import-${width}.png`)});}
  // All parts share one button, one picker call per activation, including the blank area.
  for(const target of ['.entry-import-icon','.entry-import-copy b','.entry-import .chevron']){
   const before=await page.evaluate(()=>pickCount);await page.locator(target).click();
   assert.equal(await page.evaluate(()=>pickCount),before+1);await page.evaluate(()=>beginManual());await card.scrollIntoViewIfNeeded();
  }
  rect=await card.boundingBox();let before=await page.evaluate(()=>pickCount);await card.click({position:{x:rect.width/2,y:3}});
  assert.equal(await page.evaluate(()=>pickCount),before+1);
  await page.evaluate(()=>beginManual());await card.focus();before=await page.evaluate(()=>pickCount);await page.keyboard.press('Enter');
  assert.equal(await page.evaluate(()=>pickCount),before+1);
  assert.deepEqual(errors,[]);console.log(`PASS ${width}: long press, select all 251 rows, exclusions, confirm/cancel, batch undo, large text, invalid-task suppression and full-card picker`);
  await page.close();
 }}finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
