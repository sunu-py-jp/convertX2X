import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,rm,readFile } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import os from 'node:os';
import path from 'node:path';
import { compareImages } from '../scripts/visual-compare.mjs';
const exec=promisify(execFile);

test('pixel comparison measures an isolated changed pixel and independent panels',async()=>{
  const directory=await mkdtemp(path.join(os.tmpdir(),'html2pptx-pixel-test-'));
  try {
    await exec(process.env.PYTHON||'python3',['-c',`from PIL import Image\nimport sys\na=Image.new('RGB',(20,20),'white');a.save(sys.argv[1]+'/source.png')\na.putpixel((5,7),(0,0,0));a.save(sys.argv[1]+'/target.png')\nImage.new('RGB',(21,20),'white').save(sys.argv[1]+'/different-size.png')`,directory]);
    const source=path.join(directory,'source.png'),target=path.join(directory,'target.png');
    const [metric]=await compareImages({pairs:[{slide:1,source,target,regions:[
      {id:'changed',x:0,y:0,width:10,height:10},{id:'unchanged',x:10,y:0,width:10,height:10},
    ]}],output:directory});
    assert.equal(metric.changedPixels,1);
    assert.equal(metric.changedPixelRatio,1/400);
    assert.equal(metric.meanAbsoluteChannelError,255/400);
    assert.equal(metric.rootMeanSquareChannelError,12.75);
    assert.equal(metric.regions[0].changedPixelRatio,1/100);
    assert.equal(metric.regions[1].changedPixels,0);
    assert.ok(metric.additionalPptxEdgePixels>0);
    assert.ok((await readFile(path.join(directory,metric.images.comparison))).length>0);
    const [identical]=await compareImages({pairs:[{source,target:source}],output:directory});
    assert.equal(identical.changedPixels,0);
    assert.equal(identical.meanAbsoluteChannelError,0);
    assert.equal(identical.edgeMismatchRatio,0);
    await assert.rejects(compareImages({pairs:[{source,target:path.join(directory,'different-size.png')}],output:directory}),/dimensions must match/);
  } finally {await rm(directory,{recursive:true,force:true});}
});
