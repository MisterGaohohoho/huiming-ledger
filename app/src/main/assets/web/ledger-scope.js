'use strict';
window.LedgerScope={
 channel:'',
 isMonth(value){return /^\d{4}-\d{2}$/.test(value)},
 mode(value){return value==='all'?'all':/^\d{4}$/.test(value)?'year':this.isMonth(value)?'month':'custom'},
 valid(value){return typeof value==='string'&&(/^(20\d{2}|2100)(-(0[1-9]|1[0-2]))?$/.test(value)||value==='all'||/^\d{4}-\d{2}-\d{2}\.\.\d{4}-\d{2}-\d{2}$/.test(value))},
 contains(value,time){const d=new Date(time),key=`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;return value==='all'||(this.mode(value)==='custom'?key>=value.slice(0,10)&&key<=value.slice(12):key.startsWith(value))},
 label(value){return value==='all'?'全部时间':this.mode(value)==='year'?value+'年':this.isMonth(value)?value.slice(0,4)+'年'+Number(value.slice(5))+'月':value.replace('..',' 至 ')},
 prefix(value){return ({month:'本月',year:'本年',all:'历史',custom:'本期'})[this.mode(value)]}
};
