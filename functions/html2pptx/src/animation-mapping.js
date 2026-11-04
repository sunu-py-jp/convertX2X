/** Carry DOM ownership across the editable text/shape/image export hooks. */
export function installAnimationMappingHook() {
  const previous=globalThis.__convertX2XRender;
  const targets=globalThis.__convertX2XAnimationTargets;
  const slides=[...document.querySelectorAll('[data-slide]')];
  const records=slides.map(()=>Object.create(null));
  globalThis.__convertX2XAnimationOrders=records;
  if(!targets)return;
  globalThis.__convertX2XRender=(node,result,...args)=>{
    result=previous(node,result,...args);
    const element=node.nodeType===1?node:node.parentElement;
    const root=element?.closest('[data-slide]'),index=slides.indexOf(root);
    if(index<0||!result?.items?.length)return result;
    const ids=new Set();
    for(let current=element;current;current=current.parentElement){
      for(const id of targets.get(current)||[])ids.add(id);
      if(current===root)break;
    }
    for(const id of ids){
      const orders=records[index][id]??=[];
      for(const item of result.items)if(Number.isInteger(item.domOrder)&&!orders.includes(item.domOrder))orders.push(item.domOrder);
    }
    return result;
  };
}
