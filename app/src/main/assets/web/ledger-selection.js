/* Selection is transient; confirmed deletion and its undo deadline are native durable data. */
(()=>{
 'use strict';
 let selecting=false,all=false,ids=new Set(),excluded=new Set(),scope='',summary=null,summaryKey='',press=null,suppressUntil=0;
 const entries=$('entries'),ledger=$('ledger'),header=ledger.querySelector('.v21-header');
 header.insertAdjacentHTML('beforeend','<div id="selection-heading" hidden role="status" aria-live="polite"><h1 id="selection-count"></h1><p>轻点账单勾选，长按进入多选</p></div>');
 ledger.insertAdjacentHTML('beforeend','<div id="selection-bar" hidden role="toolbar" aria-label="批量管理账单"><button type="button" id="selection-cancel">取消</button><button type="button" id="selection-all">全选</button><button type="button" class="danger" id="selection-delete">删除</button></div>');
 const rowOf=target=>target.closest('[data-record],[data-pending]');
 const idOf=row=>Number(row.dataset.record||row.dataset.pending);
 const selected=id=>all?!excluded.has(id):ids.has(id);
 function request(){return {period:month,search:query,direction:filter==='income'?'INCOME':'EXPENSE',channel:LedgerScope.channel,reimbursement:statsReimbursement,all,ids:[...ids],excluded:[...excluded]}}
 function readSummary(){
  if(!native?.previewBatchDelete)throw Error('请在新版有数中使用批量删除');
  const result=JSON.parse(native.previewBatchDelete(JSON.stringify(request())));
  if(!result.ok)throw Error(result.error||'无法读取所选账单');return result;
 }
 function scopeLabel(){return [query?'全部时间 · 搜索“'+query+'”':LedgerScope.label(month),filter==='income'?'收入':'支出',({'':'全部渠道',wechat:'微信支付',alipay:'支付宝',other:'其他渠道'})[LedgerScope.channel],statsReimbursement==='required'?'需要报销':statsReimbursement==='notRequired'?'不需要报销':''].filter(Boolean).join(' · ')}
 function draw(){
  if(selecting&&scope!==listScope()){cancel();return}
  ledger.classList.toggle('is-selecting',selecting);
  $('selection-heading').hidden=$('selection-bar').hidden=!selecting;
  entries.querySelectorAll('[data-record],[data-pending]').forEach(row=>{
   if(selecting){row.setAttribute('aria-pressed',String(selected(idOf(row))));row.classList.toggle('is-selected',selected(idOf(row)))}
   else{row.removeAttribute('aria-pressed');row.classList.remove('is-selected')}
  });
  if(!selecting)return;
  const key=JSON.stringify(request())+'|'+dataRevision;
  if(key!==summaryKey){try{summary=readSummary();summaryKey=key}catch(error){summary=null;notify(error.message)}}
  $('selection-count').textContent='已选 '+(summary?.count??ids.size)+' 笔';
  $('selection-delete').disabled=!summary?.count;
  $('selection-all').textContent=all&&!excluded.size?'取消全选':'全选';
  $('selection-all').setAttribute('aria-label',all&&!excluded.size?'取消全选':'全选当前筛选结果，包含未加载的账单');
 }
 function begin(id){
  if(!native?.previewBatchDelete){notify('请在新版有数中使用批量删除');return}
  closeSwipeRows();selecting=true;all=false;ids=new Set([id]);excluded.clear();scope=listScope();summaryKey='';draw();
 }
 function cancel(){if(!selecting)return;selecting=false;all=false;ids.clear();excluded.clear();summary=null;summaryKey='';draw()}
 function toggle(id){const set=all?excluded:ids;if(set.has(id))set.delete(id);else set.add(id);draw()}
 function stopPress(){if(press)clearTimeout(press.timer);press=null}
 entries.addEventListener('pointerdown',event=>{
  if(event.isPrimary===false||event.button>0)return;
  const row=rowOf(event.target);if(!row)return;
  if(selecting){event.stopPropagation();return}
  stopPress();press={x:event.clientX,y:event.clientY,id:event.pointerId,timer:setTimeout(()=>{
   suppressUntil=Date.now()+1000;begin(idOf(row));stopPress();
  },500)};
 },true);
 entries.addEventListener('pointermove',event=>{if(press&&Math.hypot(event.clientX-press.x,event.clientY-press.y)>10)stopPress()},true);
 document.addEventListener('pointerup',stopPress,true);document.addEventListener('pointercancel',stopPress,true);window.addEventListener('blur',stopPress);
 entries.addEventListener('contextmenu',event=>{const row=rowOf(event.target);if(!row)return;event.preventDefault();if(!selecting)begin(idOf(row))});
 entries.addEventListener('keydown',event=>{const row=rowOf(event.target);if(row&&event.shiftKey&&event.key==='F10'){event.preventDefault();begin(idOf(row))}});
 entries.addEventListener('click',event=>{
  const row=rowOf(event.target);if(!row)return;
  if(Date.now()<suppressUntil){event.preventDefault();event.stopImmediatePropagation();return}
  if(selecting){event.preventDefault();event.stopImmediatePropagation();toggle(idOf(row))}
 },true);
 $('selection-cancel').onclick=cancel;
 $('selection-all').onclick=()=>{if(all&&!excluded.size){all=false;ids.clear()}else{all=true;ids.clear();excluded.clear()}summaryKey='';draw()};
 $('selection-delete').onclick=()=>{
  let preview;try{preview=readSummary()}catch(error){notify(error.message);return}
  if(!preview.count){notify('请先选择账单');draw();return}
  showSheet(`删除 ${preview.count} 笔账单？`,
   `<p class="sheet-copy batch-scope">${escapeText(scopeLabel())}</p><dl class="batch-totals"><div><dt>所选收入</dt><dd>¥ ${money(preview.income)}</dd></div><div><dt>所选支出</dt><dd>¥ ${money(preview.expense)}</dd></div></dl><p class="sheet-copy">${preview.reviewCount?'含 '+preview.reviewCount+' 笔待确认账单（尚未计入总账）。':''}删除后同步更新总账、统计和关联账户余额，账户仍保留。10 秒内可整批撤销。</p><div class="sheet-actions"><button type="button" id="batch-cancel">取消</button><button type="button" class="danger" id="batch-confirm">确认删除</button></div>`);
  $('batch-cancel').onclick=()=>closeSheet();
  $('batch-confirm').onclick=()=>{
   const button=$('batch-confirm');button.disabled=true;
   try{
    const result=JSON.parse(native.deleteBatch(preview.token));
    if(!result.ok)throw Error(result.error||'删除失败，请重试');
    closeSheet();cancel();viewKey='';loadedScope='';refresh();showUndo(result);
   }catch(error){button.disabled=false;closeSheet();summaryKey='';draw();notify(error.message)}
  };
 };
 const renderBefore=window.render;window.render=function(){renderBefore();draw()};
 window.LedgerSelection={active:()=>selecting,cancel,back(){if(!selecting)return false;cancel();return true}};
 document.addEventListener('keydown',event=>{if(event.key==='Escape'&&selecting&&!$('sheet').open){event.preventDefault();cancel()}});
})();
