import JSZip from 'jszip';
import { configuration } from './config.js';
import { convertHtml } from './service.js';
import { ConversionError } from './errors.js';
export function createHandlers({limits=configuration(),convert=convertHtml}={}) {
  let busy=false;
  return {convert:async request=>{
    if(busy)return {status:429,headers:{'Retry-After':'10'},jsonBody:{error:{code:'BUSY',message:'別の変換を処理中です。'}}};
    busy=true;
    try {
      const filename=request.query.get('filename')||'input.html';
      const format=request.query.get('format')||'zip';
      if(!['zip','pptx'].includes(format))throw new ConversionError('INVALID_OPTION','formatはzipまたはpptxを指定してください。',400);
      const length=Number(request.headers.get('content-length'));
      if(length>limits.maxInputBytes)throw new ConversionError('INPUT_LIMIT','入力サイズが上限を超えています。',413);
      let size=0;const chunks=[];
      if(request.body)for await(const chunk of request.body){size+=chunk.byteLength;if(size>limits.maxInputBytes)throw new ConversionError('INPUT_LIMIT','入力サイズが上限を超えています。',413);chunks.push(Buffer.from(chunk));}
      if(!size)throw new ConversionError('EMPTY_INPUT','HTMLまたはZIPのバイト列を本文に指定してください。',400);
      const result=await convert(Buffer.concat(chunks),{filename,limits});
      let body=result.pptx,type='application/vnd.openxmlformats-officedocument.presentationml.presentation',outputName='presentation.pptx';
      if(format==='zip'){
        const zip=new JSZip();zip.file('presentation.pptx',result.pptx);zip.file('report.json',JSON.stringify(result.report,null,2));
        body=await zip.generateAsync({type:'nodebuffer',compression:'DEFLATE'});type='application/zip';outputName='result.zip';
      }
      return {status:200,headers:{'Content-Type':type,'Content-Disposition':`attachment; filename="${outputName}"`,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'},body};
    }catch(error){
      const known=error instanceof ConversionError;
      return {status:known?error.status:500,headers:{'Cache-Control':'no-store'},jsonBody:{error:{code:known?error.code:'CONVERSION_FAILED',message:known?error.message:'変換に失敗しました。',...(known&&error.details?{details:error.details}:{})}}};
    }finally{busy=false;}
  }};
}
