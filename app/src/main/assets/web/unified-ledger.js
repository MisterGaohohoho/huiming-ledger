/* Integrated local reports; ECharts and Personal-Bill notices are bundled with the app. */
(()=>{
 'use strict';
 const names={wechat:'微信支付',alipay:'支付宝',other:'其他渠道','':'全部渠道'};
 let chart=null,report=null,detailRevision='';
 const stats=$('stats'),header=stats.querySelector('.header-actions');
 header.insertAdjacentHTML('beforeend','<button type="button" class="icon-btn scope-filter" id="stats-filter" aria-label="筛选统计账单"><i class="fa-solid fa-sliders" aria-hidden="true"></i></button>');
 document.querySelector('#ledger .header-actions').insertAdjacentHTML('afterbegin','<button type="button" class="icon-btn scope-filter" id="ledger-filter" aria-label="筛选账单"><i class="fa-solid fa-sliders" aria-hidden="true"></i></button>');
 document.querySelector('.home-month').insertAdjacentHTML('afterend','<div id="ledger-scope-note" class="scope-note" hidden></div>');
 stats.querySelector('.stats-controls').insertAdjacentHTML('afterend','<div id="stats-scope-note" class="scope-note" hidden></div><p id="stats-error" class="scope-error" role="status" hidden></p>');
 stats.querySelector('.ranking-heading').insertAdjacentHTML('beforebegin','<section class="card integrated-trend"><h2>收支趋势</h2><div id="integrated-trend" role="img" aria-label="收支趋势图"></div><p class="muted small" id="trend-caption"></p></section>');
 $('ranking').insertAdjacentHTML('afterend','<section class="card integrated-top"><h2>大额支出</h2><div id="integrated-top"></div></section>');
 stats.querySelector('.analysis-total').insertAdjacentHTML('afterend','<div class="integrated-totals" id="integrated-totals" hidden></div>');
 function query(){return {mode:LedgerScope.mode(month),period:month,channel:LedgerScope.channel,reimbursement:statsReimbursement}}
 function read(method,params){const result=JSON.parse(native[method](JSON.stringify(params)));if(result.ok===false)throw Error(result.error||'读取失败，请重试');return result}
 function chips(){
  const labels=[LedgerScope.channel?names[LedgerScope.channel]:'',statsReimbursement==='required'?'需要报销':statsReimbursement==='notRequired'?'不需要报销':''].filter(Boolean);
  for(const id of ['ledger-scope-note','stats-scope-note']){$(id).hidden=!labels.length;$(id).innerHTML=labels.length?`<span>${labels.map(escapeText).join(' · ')}</span><button type="button" aria-label="清除筛选">清除</button>`:'';if(labels.length)$(id).querySelector('button').onclick=()=>{LedgerScope.channel='';statsReimbursement='';applyScope()}}
  for(const id of ['ledger-filter','stats-filter'])$(id).classList.toggle('is-filtered',!!labels.length);
 }
 function applyScope(){viewKey='';loadedScope='';viewLimit=200;closeSheet();refresh();if(page==='stats')drawStats();chips();remember()}
 function choosePeriod(){
  let mode=LedgerScope.mode(month);const current=localMonth(Date.now());
  showSheet('选择时间范围',`<div class="scope-modes" role="group" aria-label="时间范围">${[['month','按月'],['year','按年'],['all','全部'],['custom','自定义']].map(([v,t])=>`<button type="button" data-period-mode="${v}" aria-pressed="${mode===v}">${t}</button>`).join('')}</div><div id="period-fields"></div><p class="scope-error" id="period-error" role="status"></p><div class="sheet-actions"><button type="button" id="period-cancel">取消</button><button class="primary" id="period-apply">确定</button></div>`);
  function fields(){
   document.querySelectorAll('[data-period-mode]').forEach(b=>b.setAttribute('aria-pressed',String(mode===b.dataset.periodMode)));
   $('period-fields').innerHTML=mode==='all'?'<p class="sheet-copy">查看所有历史账单。</p>':mode==='month'?`<label class="scope-field">月份<input id="period-month" type="month" min="2000-01" max="2100-12" value="${LedgerScope.isMonth(month)?month:current}" required></label>`:mode==='year'?`<label class="scope-field">年份<input id="period-year" type="number" min="2000" max="2100" step="1" value="${/^20\d{2}|2100/.test(month)?month.slice(0,4):current.slice(0,4)}" required inputmode="numeric"></label>`:`<div class="scope-date-fields"><label class="scope-field">开始日期<input id="period-from" type="date" min="1970-01-01" max="2100-12-31" value="${month.includes('..')?month.slice(0,10):current+'-01'}" required></label><label class="scope-field">结束日期<input id="period-through" type="date" min="1970-01-01" max="2100-12-31" value="${month.includes('..')?month.slice(12):localTime(Date.now()).slice(0,10)}" required></label></div>`;
   $('period-error').textContent='';
  }
  document.querySelectorAll('[data-period-mode]').forEach(b=>b.onclick=()=>{mode=b.dataset.periodMode;fields()});fields();
  $('period-cancel').onclick=()=>closeSheet();$('period-apply').onclick=()=>{
   if([...$('period-fields').querySelectorAll('input')].some(i=>!i.value||!i.checkValidity())){$('period-error').textContent='请选择有效日期';return}
   let value=mode==='all'?'all':mode==='month'?$('period-month').value:mode==='year'?$('period-year').value:$('period-from').value+'..'+$('period-through').value;
   if(mode==='custom'&&value.slice(0,10)>value.slice(12)){$('period-error').textContent='结束日期不能早于开始日期';return}
   if(!LedgerScope.valid(value)){$('period-error').textContent='请选择有效日期';return}month=value;applyScope();
  };
 }
 function chooseFilter(){
  showSheet('筛选账单',`<label class="scope-field">支付渠道<select id="scope-channel">${Object.entries(names).map(([v,t])=>`<option value="${v}"${LedgerScope.channel===v?' selected':''}>${t}</option>`).join('')}</select></label><label class="scope-field">报销属性<select id="scope-reimbursement">${[['','全部账单'],['required','需要报销'],['notRequired','不需要报销']].map(([v,t])=>`<option value="${v}"${statsReimbursement===v?' selected':''}>${t}</option>`).join('')}</select></label><div class="sheet-actions"><button type="button" id="scope-reset">重置</button><button type="button" class="primary" id="scope-apply">确定</button></div>`);
  $('scope-reset').onclick=()=>{$('scope-channel').value='';$('scope-reimbursement').value=''};
  $('scope-apply').onclick=()=>{LedgerScope.channel=$('scope-channel').value;statsReimbursement=$('scope-reimbursement').value;applyScope()};
 }
 $('month').onclick=choosePeriod;window.chooseStatsMonth=choosePeriod;
 $('ledger-filter').onclick=chooseFilter;$('stats-filter').onclick=chooseFilter;
 const oldRender=render;window.render=function(){oldRender();chips();if(!LedgerScope.isMonth(month))$('home-change').textContent='查看收支趋势'};
 function row(r){return `<button type="button" class="unified-record" data-unified-record="${Number(r.id)}"><span><b>${escapeText(r.merchant||'未命名账单')}</b><small>${escapeText(localTime(r.occurredAt).replace('T',' '))} · ${escapeText(names[r.channel]||'其他渠道')}${r.state==='REVIEW'?' · 待确认':''}</small></span><strong>${signedMoney(r.amountCents,r.direction==='INCOME')}</strong><span aria-hidden="true">›</span></button>`}
 function bindRows(container){container.querySelectorAll('[data-unified-record]').forEach(b=>b.onclick=()=>{const bounds=b.getBoundingClientRect();closeSheet(false);openRecord(Number(b.dataset.unifiedRecord),false,bounds)})}
 function showDetails(extra={},title='账单明细'){
  let rows=[],hasMore=false;detailRevision='';const params={...query(),...extra};
  function load(){try{
   let result=read('offlineRecords',{...params,offset:rows.length,limit:50});
   if(detailRevision&&result.revision!==detailRevision){rows=[];result=read('offlineRecords',{...params,offset:0,limit:50})}
   detailRevision=result.revision;rows.push(...result.records);hasMore=result.hasMore;
   showSheet(title,`<p class="sheet-copy">${escapeText(LedgerScope.label(params.period))} · ${escapeText(names[params.channel])}</p><div>${rows.map(row).join('')||'<p class="empty-state">暂无匹配账单</p>'}</div>${hasMore?'<button type="button" id="unified-more" class="load-more-records">加载更多</button>':''}`);
   bindRows($('sheet-content'));if(hasMore)$('unified-more').onclick=load;
  }catch(error){notify(error.message)}}load();
 }
 const originalStats=drawStats;
 window.drawStats=function(){
  chips();if(!native?.offlineSummary){originalStats();return}
  $('stats-month').textContent=LedgerScope.mode(month)==='custom'?'自定义日期':LedgerScope.label(month);
  $('stats-month').parentElement.title=LedgerScope.label(month);
  try {
   report=read('offlineSummary',query());statsSummary=report;const t=report.totals,all=statsType==='all',income=statsType==='income';
   $('stats-error').hidden=true;stats.querySelector('.analysis-card').hidden=false;
   const amount=income?t.income:t.expense;
   $('analysis-label').textContent=LedgerScope.prefix(month)+(all?'收支概览':income?'收入总计':'支出总计');
   $('analysis-total').hidden=all;$('analysis-total').innerHTML='<small>¥</small> '+money(amount);
   $('integrated-totals').hidden=!all;$('integrated-totals').innerHTML=[['收入',t.income],['支出',t.expense],['结余',t.balance]].map(([label,value])=>`<div><small>${label}</small><strong>¥ ${money(value)}</strong></div>`).join('');
   const key=income?'income':'expense',prior=report.previous[key];
   $('stats-change').textContent=report.comparable&&!all?(prior>0?`较上${LedgerScope.isMonth(month)?'月':'年'}${amount>=prior?'增加':'减少'} ${Math.abs((amount-prior)/prior*100).toFixed(1)}%`:'上期无数据，暂不比较'):LedgerScope.mode(month)==='custom'?LedgerScope.label(month):'';
   const data=all?[{name:'收入',amount:t.income},{name:'支出',amount:t.expense}].filter(x=>x.amount>0):report.categories.filter(x=>x.direction===(income?'INCOME':'EXPENSE'));
   const total=data.reduce((sum,x)=>sum+x.amount,0),labels=data.length>6?[...data.slice(0,5),{name:'其他分类',amount:data.slice(5).reduce((sum,x)=>sum+x.amount,0)}]:data;let end=0;
   $('donut').style.background=total?'conic-gradient('+labels.map((d,i)=>{const start=end;end+=d.amount/total*100;return `${chartColors[i%chartColors.length]} ${start}% ${end}%`}).join(',')+')':'#e6edf2';
   $('stats-label').textContent=all?'收支笔数':income?'总收入':'总支出';$('stats-total').textContent=all?`${t.incomeCount+t.expenseCount} 笔`:'¥ '+money(amount);$('stats-count').textContent=all?'全部已入账':`共 ${t[key+'Count']} 笔`;
   $('chart-labels').innerHTML=labels.map((x,i)=>`<div class="legend-row"><i class="dot" style="background:${chartColors[i%chartColors.length]}"></i><span>${escapeText(x.name)}</span><span>${(x.amount/(total||1)*100).toFixed(0)}%</span></div>`).join('')||'<span class="muted small">暂无分类数据</span>';
   const ranked=report.categories.filter(x=>x.direction===(rankingType==='income'?'INCOME':'EXPENSE')),rankTotal=ranked.reduce((sum,x)=>sum+x.amount,0);
   $('ranking').innerHTML=ranked.map(x=>`<button type="button" class="category-row ranking-row" data-ranking-category="${escapeText(x.name)}">${categoryBadge(x.name)}<span class="category-middle ranking-info"><span class="category-name">${escapeText(x.name)}</span><span class="progress-row"><span class="progress"><i style="width:${x.amount/(rankTotal||1)*100}%"></i></span><small>${(x.amount/(rankTotal||1)*100).toFixed(0)}%</small></span></span><span class="category-amount">¥ ${money(x.amount)}</span><span class="chevron">›</span></button>`).join('')||'<div class="empty-state">所选范围暂无账单</div>';
   $('ranking').querySelectorAll('[data-ranking-category]').forEach(b=>b.onclick=()=>showDetails({category:b.dataset.rankingCategory,direction:rankingType==='income'?'INCOME':'EXPENSE'},b.dataset.rankingCategory+'明细'));
   $('integrated-top').innerHTML=report.top.map(row).join('')||'<p class="empty-state">暂无支出</p>';bindRows($('integrated-top'));
   document.querySelectorAll('#stats-type button').forEach(b=>{b.classList.toggle('active',b.dataset.type===statsType);b.setAttribute('aria-pressed',String(b.dataset.type===statsType))});
   document.querySelectorAll('#ranking-type button').forEach(b=>{b.classList.toggle('active',b.dataset.type===rankingType);b.setAttribute('aria-pressed',String(b.dataset.type===rankingType))});
   drawTrend();
  }catch(error){report=null;chart?.clear();$('stats-error').hidden=false;$('stats-error').textContent=error.message;stats.querySelector('.analysis-card').hidden=true;$('ranking').textContent='';$('integrated-top').textContent='';$('trend-caption').textContent=''}
 };
 function drawTrend(){
  if(!report)return;chart??=echarts.init($('integrated-trend'));chart.off('click');
  const keys=statsType==='all'?['expense','income']:[statsType],colors={expense:'#48b99c',income:'#87baff'};
  chart.setOption({animation:false,tooltip:{trigger:'axis',valueFormatter:v=>'¥'+Number(v).toFixed(2)},legend:{bottom:0},grid:{left:48,right:14,top:18,bottom:54},xAxis:{type:'category',data:report.trend.map(x=>x.label),axisLabel:{fontSize:10,hideOverlap:true}},yAxis:{type:'value',axisLabel:{formatter:v=>Math.abs(v)>=10000?(v/10000)+'万':v,fontSize:10}},series:keys.map(key=>({name:key==='income'?'收入':'支出',type:report.trendUnit==='day'?'line':'bar',showSymbol:false,itemStyle:{color:colors[key]},data:report.trend.map(x=>x[key]/100)}))},true);
  chart.on('click',event=>{const bucket=report?.trend[event.dataIndex];if(bucket)showDetails({mode:'custom',period:bucket.period,direction:event.seriesName==='收入'?'INCOME':'EXPENSE'},bucket.label+'明细')});
  $('trend-caption').textContent=report.trend.length?'点击图表查看对应明细；支出按退款后实付金额统计。':'所选范围暂无账单';
 }
 new ResizeObserver(()=>chart?.resize()).observe($('integrated-trend'));
 const oldGo=go;window.go=function(id){oldGo(id);if(id==='stats')chart?.resize()};
 window.UnifiedLedger={choosePeriod,chooseFilter,showDetails};chips();
})();
