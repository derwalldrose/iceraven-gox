/* Local renderer. No native JS bridge. Model text is always untrusted. */
'use strict';
const safeConfig={USE_PROFILES:{html:true,svg:true,svgFilters:false},FORBID_TAGS:['script','style','iframe','object','embed','form','input','button','textarea','video','audio','img','foreignObject','animate','set','use'],FORBID_ATTR:['style','src','srcset','id','name','target','formaction'],ALLOW_DATA_ATTR:false};
function sanitize(html){
 const box=document.createElement('div');box.innerHTML=DOMPurify.sanitize(html,safeConfig);
 box.querySelectorAll('a').forEach(a=>{const href=a.getAttribute('href')||'';if(!/^https?:\/\//i.test(href))a.removeAttribute('href');a.setAttribute('rel','noopener noreferrer')});
 return box.innerHTML;
}
function markdown(text){return sanitize(marked.parse(String(text||''),{gfm:true,breaks:true}));}
function previewDocument(text){return '<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'none\'; style-src \'unsafe-inline\'; img-src data:; form-action \'none\'; base-uri \'none\'"><style>body{font:15px system-ui;padding:12px;color:#202124;overflow-wrap:anywhere}svg{max-width:100%;height:auto}table{border-collapse:collapse}td,th{border:1px solid #aaa;padding:5px}</style>'+sanitize(text);}
function decorate(body,index,status){
 body.querySelectorAll('pre code').forEach(code=>{
  const raw=code.textContent;try{hljs.highlightElement(code)}catch(_){}
  const bar=document.createElement('div');bar.className='codebar';
  const copy=document.createElement('button');copy.textContent='复制代码';copy.onclick=()=>{location.href='gox-action://copy-code/'+index+'/'+Array.from(body.querySelectorAll('pre code')).indexOf(code)};bar.append(copy);
  if(/language-(html|svg|xml)/i.test(code.className)||/^\s*<(?:!doctype|html|svg)/i.test(raw)){
   const frameForCode=()=>{const next=code.parentElement.nextElementSibling;return next&&next.tagName==='IFRAME'?next:null};
   const show=()=>{let frame=frameForCode();if(frame)return frame;frame=document.createElement('iframe');frame.setAttribute('sandbox','');frame.setAttribute('referrerpolicy','no-referrer');frame.title='无脚本 HTML / SVG 预览';frame.srcdoc=previewDocument(raw);code.parentElement.after(frame);return frame};
   const toggle=document.createElement('button');toggle.textContent='显示/隐藏预览';bar.append(toggle);
   toggle.onclick=()=>{const frame=frameForCode();if(frame)frame.remove();else show()};
   if(status!=='生成中')show();
  }
  code.parentElement.before(bar);
 });
}
const rows=new Map();
function update(items,forceBottom){
 const near=window.innerHeight+window.scrollY>=document.documentElement.scrollHeight-100;
 document.getElementById('welcome').hidden=items.length>0;
 const root=document.getElementById('messages');
 for(const [index,row] of rows){if(index>=items.length){row.node.remove();rows.delete(index)}}
 items.forEach((item,index)=>{
  let row=rows.get(index);if(!row){const node=document.createElement('article');node.innerHTML='<header></header><div class="body"></div><footer></footer>';root.append(node);row={node,text:null,status:null};rows.set(index,row)}
  row.node.className=item.role==='user'?'user':'assistant';row.node.querySelector('header').textContent=item.role==='user'?'你':'Gox AI';
  if(row.text!==item.content||row.renderStatus!==item.status){const body=row.node.querySelector('.body');body.innerHTML=markdown(item.content);decorate(body,index,item.status);row.text=item.content;row.renderStatus=item.status}
  if(row.status!==item.status){const foot=row.node.querySelector('footer');foot.replaceChildren();const state=document.createElement('span');state.className='state';state.textContent=item.status||'';foot.append(state);const copy=document.createElement('button');copy.type='button';copy.textContent='⧉ 复制';copy.onclick=()=>{location.href='gox-action://copy/'+index};foot.append(copy);if(item.role==='assistant'&&item.status!=='生成中'){const retry=document.createElement('button');retry.type='button';retry.textContent='↻ 重试';retry.onclick=()=>{location.href='gox-action://retry/'+index};foot.append(retry)}row.status=item.status}
 });
 if(near||forceBottom)requestAnimationFrame(()=>window.scrollTo(0,document.documentElement.scrollHeight));
}
window.GoxChat={update,markdown,sanitize,previewDocument};
