/* Offline adaptation of Personal-Bill's monthly/yearly analysis and ECharts presentation.
 * Original project: https://github.com/MisterGaohohoho/Personal-Bill (MIT; notice bundled).
 * Financial aggregation and mutations use the native ledger exclusively. */
(()=>{
 'use strict';
 const bridge=window.LedgerNative,byId=id=>document.getElementById(id),escape=value=>{const e=document.createElement('span');e.textContent=value??'';return e.innerHTML.replace(/"/g,'&quot;').replace(/'/g,'&#39;')};
 const fmt=cents=>new Intl.NumberFormat('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2}).format(Number(cents||0)/100);
 const now=new Date(),currentMonth=`${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}`;
 const names={wechat:'微信',alipay:'支付宝',other:'其他渠道','':'全部渠道'};
 let channel='wechat',reportReturn='stats',rows=[],listRevision='',charts=[],searchTimer;
 const workspace=document.querySelector('.workspace');
 workspace.insertAdjacentHTML('beforeend',`
  <section id="channel-ledger" class="screen app offline-screen" aria-label="平台账单管理">
   <header class="offline-header"><button type="button" class="offline-back" id="channel-back" aria-label="返回首页">‹</button><div><h1 id="channel-title">微信账单</h1><p>与首页账本同步 · 数据保存在本机</p></div></header>
   <div class="offline-actions"><button type="button" id="channel-import">导入账单</button><button type="button" id="channel-report">查看分析</button></div>
   <div class="offline-filters">
    <label>账单月份<input id="channel-month" type="month" min="2000-01" max="2100-12" value="${currentMonth}"></label>
    <label>收支类型<select id="channel-direction"><option value="">全部收支</option><option value="EXPENSE">支出</option><option value="INCOME">收入</option></select></label>
    <label>排序<select id="channel-sort"><option value="time">最新在前</option><option value="amount">金额从高到低</option></select></label>
    <label>切换平台<select id="channel-switch"><option value="wechat">微信</option><option value="alipay">支付宝</option></select></label>
    <label class="offline-wide">搜索本月账单<input id="channel-search" type="search" maxlength="200" placeholder="商户、备注或订单号"></label>
   </div>
   <div id="channel-totals" class="offline-totals"></div>
   <p class="offline-copy">汇总仅计算已入账记录。点击账单可编辑、标记报销或删除，修改后首页和分析同步更新。</p>
   <div id="channel-status" class="offline-status" role="status"></div>
   <div id="channel-records"></div><button id="channel-more" type="button" class="offline-more" hidden>加载更多</button>
  </section>
  <section id="offline-analysis" class="screen app offline-screen" aria-label="离线账单分析">
   <header class="offline-header"><button type="button" class="offline-back" id="analysis-back" aria-label="返回">‹</button><div><h1>月报与年报</h1><p>从每一笔账单，看清收支变化</p></div></header>
   <div class="offline-filters">
    <label>统计周期<select id="analysis-mode"><option value="month">月度报告</option><option value="year">年度报告</option></select></label>
    <label id="analysis-date-label">月份<input id="analysis-month" type="month" min="2000-01" max="2100-12" value="${currentMonth}"><input id="analysis-year" type="number" min="2000" max="2100" value="${now.getFullYear()}" hidden aria-label="年份"></label>
    <label>支付渠道<select id="analysis-channel"><option value="">全部渠道</option><option value="wechat">微信</option><option value="alipay">支付宝</option><option value="other">其他渠道</option></select></label>
    <label>报销属性<select id="analysis-reimbursement"><option value="">全部账单</option><option value="required">需要报销</option><option value="notRequired">不需要报销</option></select></label>
   </div>
   <div id="analysis-status" class="offline-status" role="status"></div><div id="analysis-totals" class="offline-totals"></div>
   <div id="analysis-budget" class="offline-card offline-budget" hidden></div>
   <section class="offline-card"><h2 id="analysis-trend-title">每日收支趋势</h2><div id="analysis-trend" class="offline-chart" role="img" aria-label="收支趋势图"></div><p id="analysis-trend-text"></p></section>
   <section class="offline-card"><div class="offline-card-head"><h2>分类分布</h2><select id="analysis-direction" aria-label="分类统计收支类型"><option value="EXPENSE">支出</option><option value="INCOME">收入</option></select></div><div id="analysis-category" class="offline-chart" role="img" aria-label="分类占比图"></div><div id="analysis-ranking"></div></section>
   <section class="offline-card"><h2>大额支出 · 前十笔</h2><div id="analysis-top"></div></section>
   <p class="offline-copy">支出按退款后的实付金额统计。已分配的报销回款不重复计入普通收入，账户转账不计收支。收支结余不等于账户余额。</p>
  </section>`);
 const entry=document.createElement('div');entry.className='offline-entry-row';entry.innerHTML='<button type="button" data-open-channel="wechat">微信账单 ›</button><button type="button" data-open-channel="alipay">支付宝账单 ›</button>';
 document.querySelector('#ledger .home-month').before(entry);
 entry.querySelectorAll('button').forEach(button=>button.onclick=()=>openChannel(button.dataset.openChannel));
 const analysisEntry=document.createElement('button');analysisEntry.type='button';analysisEntry.className='offline-analysis-entry';analysisEntry.id='open-offline-analysis';analysisEntry.textContent='月报与年报 · 全部 / 微信 / 支付宝 ›';
 byId('stats').querySelector('header').after(analysisEntry);analysisEntry.onclick=()=>openAnalysis('','stats');
 function request(method,params){if(!bridge?.[method])throw Error('请在 APK 内查看本地账单');const result=JSON.parse(bridge[method](JSON.stringify(params)));if(!result.ok)throw Error(result.error||'读取失败，请重试');return result}
 function status(id,message,error=false){const e=byId(id);e.textContent=message;e.classList.toggle('error',error)}
 function totals(t,previous=null){return `<div class="offline-total"><small>支出</small><strong>¥${fmt(t.expense)}</strong><span>${t.expenseCount||0} 笔</span></div><div class="offline-total"><small>收入</small><strong>¥${fmt(t.income)}</strong><span>${t.incomeCount||0} 笔</span></div>${previous?`<div class="offline-total balance"><small>收支结余</small><strong>¥${fmt(t.balance)}</strong><span>${previous.expense>0?`支出较上期${t.expense>=previous.expense?'增加':'减少'} ${Math.abs((t.expense-previous.expense)/previous.expense*100).toFixed(1)}%`:'上期无支出，暂不计算增减比例'}</span></div>`:''}`}
 function rowHtml(row){const income=row.direction==='INCOME';return `<button type="button" class="offline-record" data-offline-record="${Number(row.id)}"><span class="description"><b>${escape(row.merchant)}</b><small>${escape(new Date(row.occurredAt).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}))} · ${escape(row.category)}${row.state==='REVIEW'?' · <span class="pending">待确认</span>':''}</small></span><strong class="${income?'income':''}">${income?'+':'−'}¥${fmt(row.amountCents)}</strong><span aria-hidden="true">›</span></button>`}
 function bindRecords(container){container.querySelectorAll('[data-offline-record]').forEach(button=>button.onclick=()=>openRecord(Number(button.dataset.offlineRecord),false,button.getBoundingClientRect()))}
 function channelQuery(){return {mode:'month',period:byId('channel-month').value,channel,direction:byId('channel-direction').value,sort:byId('channel-sort').value,search:byId('channel-search').value.trim(),management:true,limit:50}}
 function loadChannel(reset=true){
  try{const q=channelQuery();if(reset){rows=[];listRevision='';const summary=request('offlineSummary',{mode:q.mode,period:q.period,channel});byId('channel-totals').innerHTML=totals(summary.totals)}
   const result=request('offlineRecords',{...q,offset:rows.length});
   if(!reset&&listRevision&&result.revision!==listRevision){loadChannel(true);return}
   listRevision=result.revision;rows.push(...result.records);byId('channel-records').innerHTML=rows.map(rowHtml).join('')||'<p class="offline-empty">本月暂无匹配账单，可调整月份或导入官方账单。</p>';bindRecords(byId('channel-records'));
   byId('channel-more').hidden=!result.hasMore;status('channel-status',`已显示 ${rows.length} 笔${result.hasMore?'，可继续加载':''}`);
  }catch(error){byId('channel-more').hidden=true;byId('channel-records').innerHTML='';byId('channel-totals').innerHTML='';status('channel-status',error.message,true)}
 }
 function reportQuery(){const mode=byId('analysis-mode').value;return {mode,period:byId(mode==='year'?'analysis-year':'analysis-month').value,channel:byId('analysis-channel').value,reimbursement:byId('analysis-reimbursement').value}}
 function clearCharts(){charts.forEach(chart=>chart.dispose());charts=[]}
 let report=null;
 function renderReport(){
  try{const q=reportQuery();report=request('offlineSummary',q);const t=report.totals;
   byId('analysis-totals').innerHTML=totals(t,report.previous);status('analysis-status',`${q.period} · ${names[q.channel]} · ${t.expenseCount+t.incomeCount} 笔已入账`);
   byId('analysis-top').innerHTML=report.top.map(rowHtml).join('')||'<p class="offline-empty">本期暂无支出</p>';bindRecords(byId('analysis-top'));
   byId('analysis-trend-title').textContent=q.mode==='year'?'每月收支对比':'每日收支趋势';byId('analysis-trend-text').textContent=`本期收入 ¥${fmt(t.income)}，支出 ¥${fmt(t.expense)}。`;
   const budget=Number(report.budgetCents||0),budgetVisible=budget>0&&q.mode==='month'&&q.period===currentMonth&&!q.channel&&!q.reimbursement;
   byId('analysis-budget').hidden=!budgetVisible;if(budgetVisible)byId('analysis-budget').innerHTML=`<h2>本月预算</h2><progress max="${budget}" value="${Math.min(budget,t.expense)}" aria-label="月度预算已使用"></progress><p>已用 ¥${fmt(t.expense)} / ¥${fmt(budget)} · ${t.expense>budget?'超出':'剩余'} ¥${fmt(Math.abs(budget-t.expense))}</p>`;
   drawCharts();
  }catch(error){report=null;clearCharts();byId('analysis-totals').innerHTML='';byId('analysis-top').innerHTML='';byId('analysis-ranking').innerHTML='';byId('analysis-budget').hidden=true;status('analysis-status',error.message,true)}
 }
 function drawCharts(){
  clearCharts();if(!report)return;const q=reportQuery(),direction=byId('analysis-direction').value,items=report.categories.filter(x=>x.direction===direction),sum=items.reduce((n,x)=>n+x.amount,0);
  byId('analysis-ranking').innerHTML=items.map(x=>`<button type="button" class="offline-rank" data-offline-category="${escape(x.name)}"><span class="description"><b>${escape(x.name)}</b><small>${x.count} 笔 · ${sum?(x.amount/sum*100).toFixed(1):'0'}%</small></span><strong>¥${fmt(x.amount)}</strong><span aria-hidden="true">›</span></button>`).join('')||'<p class="offline-empty">暂无这类收支</p>';
  byId('analysis-ranking').querySelectorAll('button').forEach(button=>button.onclick=()=>showCategory(button.dataset.offlineCategory,direction));
  if(!window.echarts){status('analysis-status','图表组件不可用，仍可查看上方汇总与分类明细',true);return}
  const trend=echarts.init(byId('analysis-trend')),category=echarts.init(byId('analysis-category'));charts=[trend,category];
  trend.setOption({animation:false,color:['#287d53','#ca7651'],tooltip:{trigger:'axis',valueFormatter:v=>'¥'+Number(v).toFixed(2)},legend:{bottom:0,data:['收入','支出']},grid:{left:52,right:12,top:20,bottom:54},xAxis:{type:'category',data:report.trend.map(x=>x.index+(q.mode==='year'?'月':'日')),axisLabel:{fontSize:10}},yAxis:{type:'value',axisLabel:{formatter:v=>Math.abs(v)>=10000?(v/10000)+'万':v}},series:['income','expense'].map((key,i)=>({name:i?'支出':'收入',type:q.mode==='year'?'bar':'line',showSymbol:false,data:report.trend.map(x=>x[key]/100),smooth:false}))});
  category.setOption({animation:false,color:['#287d53','#6e9d80','#b9cfa8','#d6a26f','#7796ad','#8c82a3','#d2ba91'],tooltip:{trigger:'item',formatter:p=>`${escape(p.name)}：¥${Number(p.value).toFixed(2)} (${p.percent}%)`},legend:{bottom:0,type:'scroll'},series:[{type:'pie',radius:['35%','64%'],center:['50%','42%'],label:{show:false},data:items.filter(x=>x.amount>0).map(x=>({name:x.name,value:x.amount/100}))}]});
  category.on('click',p=>showCategory(p.name,direction));
 }
 function showCategory(category,direction){
  let detailRows=[];const q={...reportQuery(),category,direction,limit:50};
  function load(){try{const result=request('offlineRecords',{...q,offset:detailRows.length});detailRows.push(...result.records);showSheet(`${category} · 明细`,`${detailRows.map(rowHtml).join('')||'<p class="offline-empty">暂无账单</p>'}${result.hasMore?'<button type="button" class="offline-more" id="offline-detail-more">加载更多</button>':''}`);bindRecords(byId('sheet-content'));if(result.hasMore)byId('offline-detail-more').onclick=load}catch(error){notify(error.message)}}load();
 }
 function openChannel(value){if(!['wechat','alipay'].includes(value))return;channel=value;byId('channel-title').textContent=names[channel]+'账单';byId('channel-switch').value=channel;byId('channel-search').value='';go('channel-ledger')}
 function openAnalysis(value='',back='stats'){reportReturn=back;byId('analysis-channel').value=value;byId('analysis-month').value=back==='channel-ledger'?byId('channel-month').value:currentMonth;go('offline-analysis')}
 byId('channel-back').onclick=()=>go('ledger');byId('analysis-back').onclick=()=>go(reportReturn);byId('channel-switch').onchange=e=>openChannel(e.target.value);
 byId('channel-import').onclick=()=>{if(bridge?.importPlatformBills)bridge.importPlatformBills(channel);else notify('请在 APK 中导入账单')};byId('channel-report').onclick=()=>openAnalysis(channel,'channel-ledger');byId('channel-more').onclick=()=>loadChannel(false);
 ['channel-month','channel-direction','channel-sort'].forEach(id=>byId(id).onchange=()=>loadChannel());
 byId('channel-search').oninput=()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>{if(page==='channel-ledger')loadChannel()},200)};
 ['analysis-month','analysis-year','analysis-channel','analysis-reimbursement'].forEach(id=>byId(id).onchange=renderReport);
 byId('analysis-mode').onchange=()=>{const year=byId('analysis-mode').value==='year';byId('analysis-month').hidden=year;byId('analysis-year').hidden=!year;byId('analysis-date-label').firstChild.textContent=year?'年份':'月份';renderReport()};byId('analysis-direction').onchange=drawCharts;
 const originalGo=go;window.go=function(id){clearTimeout(searchTimer);if(id!=='offline-analysis')clearCharts();originalGo(id);if(id==='channel-ledger')loadChannel();if(id==='offline-analysis')renderReport()};
 const originalEvent=LedgerUI.event;LedgerUI.event=function(type,data){originalEvent.call(this,type,data);if(type==='refresh'){if(page==='channel-ledger')loadChannel();if(page==='offline-analysis')renderReport()}};
 const originalBack=LedgerUI.back;LedgerUI.back=function(){if(!recordPanelOpen&&!recordPanelClosing&&!manualOpen&&!manualClosing&&!byId('sheet').open&&page==='offline-analysis'){go(reportReturn);return true}return originalBack.call(this)};
 const observer=new ResizeObserver(()=>charts.forEach(chart=>chart.resize()));observer.observe(byId('analysis-trend'));observer.observe(byId('analysis-category'));
 window.OfflineBills={openChannel,openAnalysis,refresh(){if(page==='channel-ledger')loadChannel();if(page==='offline-analysis')renderReport()}};
})();
