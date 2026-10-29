export const playground=`<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>HTML → PowerPoint</title>
<style>body{font-family:system-ui,sans-serif;background:#f5f7fb;color:#182d46;max-width:900px;margin:60px auto;padding:24px}main{padding:32px;background:white;border:1px solid #dae1eb;border-radius:16px}h1{font-size:32px}label{display:block;margin:24px 0 8px}textarea{box-sizing:border-box;width:100%;height:260px;font:14px monospace;padding:12px;border:1px solid #b8c6d8;border-radius:8px}input{max-width:100%}button{display:block;margin-top:24px;background:#215cdf;color:white;border:0;padding:13px 24px;border-radius:8px;font-size:16px;cursor:pointer}button:disabled{opacity:.5}#status{white-space:pre-wrap;line-height:1.7}a{color:#215cdf}small{line-height:1.7;display:block}</style>
<main><h1>HTMLを、編集できるPowerPointへ。</h1><p>文字・表・SVG図解を編集可能な要素に変換します。写真やスクリーンショットは画像として保持します。</p>
<label for="file">HTML / ZIPを選択</label><input id="file" type="file" accept=".html,.htm,.zip"><label for="html">またはHTMLを入力</label><textarea id="html" spellcheck="false" placeholder="&lt;h1&gt;四半期の成果&lt;/h1&gt;"></textarea>
<small>複数枚は data-slide を付けた要素で区切ります。外部URL・入力JavaScriptは使用できません。ZIPには index.html と画像・CSSをまとめてください。</small>
<label for="key">Functionキー（Azureで必要）</label><input id="key" type="password" autocomplete="off"><button id="convert">PowerPointに変換</button><p id="status" role="status"></p></main>
<script>
const button=document.getElementById('convert'),status=document.getElementById('status');let downloadUrl;
button.onclick=async()=>{button.disabled=true;status.textContent='変換中…';try{
const file=document.getElementById('file').files[0],html=document.getElementById('html').value,key=document.getElementById('key').value;
if(!file&&!html.trim())throw Error('HTMLを入力するか、ファイルを選択してください。');
const response=await fetch('convert?filename='+encodeURIComponent(file?file.name:'input.html')+'&format=zip',{method:'POST',headers:{'Content-Type':'application/octet-stream',...(key?{'x-functions-key':key}:{})},body:file||html});
if(!response.ok){const result=await response.json();throw Error(result.error?.message||'変換に失敗しました。');}
if(downloadUrl)URL.revokeObjectURL(downloadUrl);downloadUrl=URL.createObjectURL(await response.blob());
status.textContent='変換が完了しました。';const link=document.createElement('a');link.href=downloadUrl;link.download='result.zip';link.textContent=' PPTXと変換レポートを保存';status.append(link);
}catch(error){status.textContent=error.message;}finally{button.disabled=false;}};
</script></html>`;
