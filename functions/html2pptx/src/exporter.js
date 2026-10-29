import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {createHash} from 'node:crypto';
const BUNDLE=path.join(path.dirname(fileURLToPath(import.meta.resolve('dom-to-pptx'))),'dom-to-pptx.bundle.js');
const HASH='df8fca27232864a4ad01d7ad8a5be1e867ab26b9562a3e5e66c8429b644256d4';
let cached;
export async function exporterSource(){
  if(cached)return cached;
  const bytes=await readFile(BUNDLE);if(createHash('sha256').update(bytes).digest('hex')!==HASH)throw new Error('Review the pinned DOM exporter before upgrading.');
  let source=bytes.toString('utf8').replaceAll('\r\n','\n');
  const original='const result = prepareRenderItem(node, { ...layoutConfig}, order, pptx, currentSortKey, nodeStyle, {';
  if(source.split(original).length!==2)throw new Error('DOM exporter hook location mismatch.');
  source=source.replace(original,original.replace('const result','let result'));
  const marker='      if (result) {\n        if (result.items) {';
  if(source.split(marker).length!==2)throw new Error('DOM exporter result hook location mismatch.');
  source=source.replace(marker,'      result = globalThis.__convertX2XRender(node,result,layoutConfig,order,currentSortKey,parentOpacity);\n'+marker);
  // Keep the original license header and source on disk unchanged; only trusted in-memory code is adapted.
  cached=source;return cached;
}
