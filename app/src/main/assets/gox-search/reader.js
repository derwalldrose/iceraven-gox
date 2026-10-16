/* Only the dedicated AI session has a native receiver; never execute model/page instructions. */
(async () => {
  const text = el => (el && (el.innerText || el.textContent) || '').trim();
  const cleanUrl = value => {
    try {
      let u=new URL(value,location.href);
      if(u.hostname.endsWith('bing.com') && u.pathname==='/ck/a') {
        const encoded=u.searchParams.get('u');
        if(encoded && encoded.startsWith('a1')) u=new URL(atob(encoded.slice(2).replace(/-/g,'+').replace(/_/g,'/')));
      }
      if(u.hostname.endsWith('duckduckgo.com') && u.searchParams.has('uddg')) u=new URL(u.searchParams.get('uddg'));
      return u.protocol==='https:' ? u.href : null;
    } catch (_) { return null; }
  };
  for (let n=0;n<16;n++) {
    const result={url:location.href,title:document.title,results:[],text:'',blocked:false};
    const seen=new Set();
    const add=(a,row)=>{
      const url=a && cleanUrl(a.href), title=text(a);
      if(!url || !title || seen.has(url)) return;
      seen.add(url);result.results.push({title,url,snippet:text(row).slice(0,1600)});
    };
    for(const row of document.querySelectorAll('#b_results .b_algo,.b_algo,.result,.g,[data-sokoban-container]')) {
      const a=row.querySelector('h2 a,h3 a,a.result__a,a[data-testid="result-title-a"]') || row.querySelector('a:has(h3)');
      add(a,row);
    }
    if(!result.results.length) {
      for(const a of document.querySelectorAll('h2 a,h3 a,a.result__a,a[data-testid="result-title-a"],a:has(h3)')) add(a,a.closest('li,article,.result')||a.parentElement);
    }
    result.results=result.results.slice(0,6);
    const main=document.querySelector('article,main,[role=main],#b_results')||document.body;
    result.text=text(main).slice(0,16000);
    result.blocked=!!document.querySelector('iframe[src*="captcha"],#b_captcha,#challenge-form') || /verify you are human|unusual traffic|请完成验证|人机验证/i.test(result.text);
    const searchPage=/\/search|duckduckgo\.com/.test(location.href);
    if(!searchPage || result.results.length || result.blocked || n===15) {
      try {await browser.runtime.sendNativeMessage('gox_search',result);} catch(_) {}
      return;
    }
    await new Promise(r=>setTimeout(r,1000));
  }
})();
