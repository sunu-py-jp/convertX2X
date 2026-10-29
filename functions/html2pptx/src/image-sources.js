/** Replace validated inline image sources with compact, trusted transport IDs. */
export function poolDocumentImages(document) {
  const sources=[],ids=new Map(),walk=[document];
  while(walk.length){
    const node=walk.pop();walk.push(...(node.childNodes||[]));
    if(node.tagName!=='img')continue;
    const source=node.attrs.find(a=>a.name==='src');if(!source)continue;
    if(!ids.has(source.value)){ids.set(source.value,sources.length);sources.push(source.value);}
    node.attrs=node.attrs.filter(a=>a.name!=='data-convertx2x-image');
    node.attrs.push({name:'data-convertx2x-image',value:String(ids.get(source.value))});
    source.value='';
  }
  return sources;
}

/** Trusted browser code; restoring data URIs never makes a network request. */
export function restoreDocumentImages(sources) {
  for(const image of document.querySelectorAll('img[data-convertx2x-image]')){
    image.src=sources[Number(image.getAttribute('data-convertx2x-image'))];
    image.removeAttribute('data-convertx2x-image');
  }
}
