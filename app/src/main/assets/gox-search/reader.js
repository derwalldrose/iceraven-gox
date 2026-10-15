/* Read-only content extraction. No page scripts or model text are executed. */
(async () => {
  for (let n=0;n<12;n++) {
    const result = {url:location.href,title:document.title,results:[],text:''};
    const rows = document.querySelectorAll('#b_results .b_algo, .result, .g');
    for (const row of rows) {
      const a=row.querySelector('h2 a,h3 a,a.result__a') || row.querySelector('a:has(h3)');
      if(a && /^https:\/\//.test(a.href)) result.results.push({title:a.innerText,url:a.href,snippet:row.innerText.slice(0,1600)});
      if(result.results.length>=6) break;
    }
    const searchPage=/\/search|duckduckgo\.com/.test(location.href);
    if (!searchPage || result.results.length || n===11) {
      result.text=(document.querySelector('article,main,[role=main]')||document.body).innerText.slice(0,16000);
      try { await browser.runtime.sendNativeMessage('gox_search',result); } catch (_) {}
      return;
    }
    await new Promise(r=>setTimeout(r,1000));
  }
})();
