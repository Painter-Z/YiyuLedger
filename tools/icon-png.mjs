// Deterministic native icon resources, rasterized from the same geometric mark as SVG.
import { deflateSync } from 'node:zlib';
const table = new Uint32Array(256);
for (let n=0;n<256;n++){let c=n;for(let k=0;k<8;k++)c=(c&1)?0xEDB88320^(c>>>1):c>>>1;table[n]=c;}
function crc(buffer){let c=0xFFFFFFFF;for(const byte of buffer)c=table[(c^byte)&255]^(c>>>8);return (c^0xFFFFFFFF)>>>0;}
function chunk(type,data){const name=Buffer.from(type);const size=Buffer.alloc(4);size.writeUInt32BE(data.length);const check=Buffer.alloc(4);check.writeUInt32BE(crc(Buffer.concat([name,data])));return Buffer.concat([size,name,data,check]);}
function rounded(x,y,left,top,width,height,radius){const cx=Math.max(left+radius,Math.min(x,left+width-radius));const cy=Math.max(top+radius,Math.min(y,top+height-radius));return x>=left&&x<=left+width&&y>=top&&y<=top+height&&(x-cx)**2+(y-cy)**2<=radius**2;}
export function iconPng(mode='app', size=512){
  const rows=Buffer.alloc((size*4+1)*size);const green=[35,66,54],pale=[224,237,204];
  for(let y=0;y<size;y++)for(let x=0;x<size;x++){
    let r=0,g=0,b=0,a=0;
    for(const dx of [.25,.75])for(const dy of [.25,.75]){
      const vx=(x+dx)/size*100,vy=(y+dy)/size*100;
      const bar=[[31,45,55],[43,30,70],[55,36,64],[67,43,57]].some(([bx,y1,y2])=>rounded(vx,vy,bx-3.25,y1-3.25,6.5,y2-y1+6.5,3.25));
      const bg=mode==='background'||mode==='app'&&rounded(vx,vy,0,0,100,100,28);
      const color=mode!=='background'&&bar?pale:bg?green:null;
      if(color){r+=color[0];g+=color[1];b+=color[2];a++;}
    }
    const i=y*(size*4+1)+1+x*4;
    rows[i]=a?r/a:0;rows[i+1]=a?g/a:0;rows[i+2]=a?b/a:0;rows[i+3]=a/4*255;
  }
  const header=Buffer.alloc(13);header.writeUInt32BE(size,0);header.writeUInt32BE(size,4);header[8]=8;header[9]=6;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]);
}
