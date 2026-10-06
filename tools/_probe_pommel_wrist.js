#!/usr/bin/env node
/**
 * _probe_pommel_wrist.js — 探针：静止 + 挥砍全程，pommel/grip 相对 wrist 的漂移。
 * 复用 verify_pose_v3 的变换约定（文件系→内部系 + Rz·Ry·Rx + 骨/世界矩阵）。
 * 目的：判定"手没锁在剑上"的程度 —— 若挥砍中 pommel 远离静止 wrist，则需让右臂跟剑走。
 */
'use strict';
const fs = require('fs');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const NS = 'catoxidesbattlerebuild';
const B = ROOT + '/build/resources/main';
const readJ = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));

const geo = readJ(`${B}/spark_models/entity/${NS}/player_wrist.json`)['minecraft:geometry'][0];
const bones = {}; const order = []; const parents = {};
for (const b of geo.bones) { bones[b.name] = b; order.push(b.name); parents[b.name] = b.parent || null; }

function toInternalBone(b) {
  const o = { name: b.name, parent: b.parent, pivot: [-b.pivot[0], b.pivot[1], b.pivot[2]] };
  if (b.rotation) o.rotation = [-b.rotation[0], -b.rotation[1], b.rotation[2]];
  if (b.cubes) o.cubes = b.cubes.map((c) => {
    const n = { origin: [-(c.origin[0] + c.size[0]), c.origin[1], c.origin[2]], size: c.size.slice() };
    if (c.pivot) n.pivot = [-c.pivot[0], c.pivot[1], c.pivot[2]];
    if (c.rotation) n.rotation = [-c.rotation[0], -c.rotation[1], c.rotation[2]];
    return n;
  });
  return o;
}
for (const n of Object.keys(bones)) bones[n] = toInternalBone(bones[n]);

const DEG = Math.PI / 180;
const V = { add: (a,b)=>[a[0]+b[0],a[1]+b[1],a[2]+b[2]], sub:(a,b)=>[a[0]-b[0],a[1]-b[1],a[2]-b[2]] };
function mat4I(){return[[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]];}
function matMul(a,b){const r=mat4I();for(let i=0;i<4;i++)for(let j=0;j<4;j++){let s=0;for(let k=0;k<4;k++)s+=a[i][k]*b[k][j];r[i][j]=s;}return r;}
function matT(v){const m=mat4I();m[0][3]=v[0];m[1][3]=v[1];m[2][3]=v[2];return m;}
function rotX(a){const c=Math.cos(a),s=Math.sin(a);return[[1,0,0,0],[0,c,-s,0],[0,s,c,0],[0,0,0,1]];}
function rotY(a){const c=Math.cos(a),s=Math.sin(a);return[[c,0,s,0],[0,1,0,0],[-s,0,c,0],[0,0,0,1]];}
function rotZ(a){const c=Math.cos(a),s=Math.sin(a);return[[c,-s,0,0],[s,c,0,0],[0,0,1,0],[0,0,0,1]];}
function rotZYX(e){const[x,y,z]=e.map(d=>d*DEG);return matMul(rotZ(z),matMul(rotY(y),rotX(x)));}
function matApply(m,v){return[m[0][0]*v[0]+m[0][1]*v[1]+m[0][2]*v[2]+m[0][3],m[1][0]*v[0]+m[1][1]*v[1]+m[1][2]*v[2]+m[1][3],m[2][0]*v[0]+m[2][1]*v[1]+m[2][2]*v[2]+m[2][3]];}
function boneLocal(name,anim){const b=bones[name];const pos=(anim&&anim.position)||[0,0,0];const rot=V.add((anim&&anim.rotation)||[0,0,0],b.rotation||[0,0,0]);const scl=(anim&&anim.scale)||[1,1,1];let m=matT(V.add(b.pivot,pos));m=matMul(m,rotZYX(rot));const ms=mat4I();ms[0][0]=scl[0];ms[1][1]=scl[1];ms[2][2]=scl[2];m=matMul(m,ms);m=matMul(m,matT([-b.pivot[0],-b.pivot[1],-b.pivot[2]]));return m;}
function boneWorld(name,anims){let m=mat4I();let cur=name;const chain=[];while(cur){chain.unshift(cur);cur=parents[cur];}for(const n of chain)m=matMul(m,boneLocal(n,anims[n]||null));return m;}
function cubeCenter(boneName,cubeIdx,anims){const b=bones[boneName];const c=b.cubes[cubeIdx];const cp=c.pivot||[c.origin[0]+c.size[0]/2,c.origin[1]+c.size[1]/2,c.origin[2]+c.size[2]/2];let m=boneWorld(boneName,anims);m=matMul(m,matT(cp));if(c.rotation)m=matMul(m,rotZYX(c.rotation));m=matMul(m,matT([-cp[0],-cp[1],-cp[2]]));const cx=c.origin[0]+c.size[0]/2,cy=c.origin[1]+c.size[1]/2,cz=c.origin[2]+c.size[2]/2;return matApply(m,[cx,cy,cz]);}

function valOf(v){return Array.isArray(v)?v.slice():(v.post||v.pre||[0,0,0]).slice();}
function sampleKeyframes(kfs,t){if(!kfs)return[0,0,0];const e=Object.entries(kfs).map(([k,v])=>[Number(k),v]).sort((a,b)=>a[0]-b[0]);if(e.length===1)return valOf(e[0][1]);if(t<=e[0][0])return valOf(e[0][1]);if(t>=e[e.length-1][0])return valOf(e[e.length-1][1]);for(let i=0;i<e.length-1;i++){if(t>=e[i][0]&&t<=e[i+1][0]){const va=valOf(e[i][1]),vb=valOf(e[i+1][1]);const f=(t-e[i][0])/(e[i+1][0]-e[i][0]);return va.map((x,k)=>x+(vb[k]-x)*f);}}return[0,0,0];}
function sampleAnim(animJson,animName,t){const a=animJson.animations[animName];const out={};for(const[bn,bd]of Object.entries(a.bones||{})){const o={};for(const prop of['position','rotation','scale']){if(!bd[prop])continue;let v=sampleKeyframes(bd[prop],t);if(prop==='position')v=[-v[0],v[1],v[2]];else if(prop==='rotation')v=[-v[0],-v[1],v[2]];o[prop]=v;}if(Object.keys(o).length)out[bn]=o;}return out;}

const still=readJ(`${B}/spark_animations/${NS}/entity/player_wrist/3rd_still.json`);
const attack=readJ(`${B}/spark_animations/${NS}/entity/player_wrist/3rd_attack.json`);
const idle=readJ(`${B}/spark_animations/${NS}/entity/player_wrist/base_state.json`);

// 关键点：wrist(rightItem pivot)、pommel(handle cube4 中心)、grip(handle cube3 中心)、tip(blade 最远角)
function keyPts(anims){
  const wrist=matApply(boneWorld('rightItem',anims),bones.rightItem.pivot);
  const shoulder=matApply(boneWorld('rightArm',anims),bones.rightArm.pivot);
  const pommel=cubeCenter('handle',4,anims);
  const grip=cubeCenter('handle',3,anims);
  // 剑尖 = blade cube0/1 里离 pommel 最远的角
  const corners=[];
  for(const ci of[0,1]){const c=bones.blade.cubes[ci];const cp=c.pivot||[c.origin[0]+c.size[0]/2,c.origin[1]+c.size[1]/2,c.origin[2]+c.size[2]/2];let m=boneWorld('blade',anims);m=matMul(m,matT(cp));if(c.rotation)m=matMul(m,rotZYX(c.rotation));m=matMul(m,matT([-cp[0],-cp[1],-cp[2]]));for(const dx of[0,c.size[0]])for(const dy of[0,c.size[1]])for(const dz of[0,c.size[2]])corners.push(matApply(m,[c.origin[0]+dx,c.origin[1]+dy,c.origin[2]+dz]));}
  const tip=corners.reduce((a,b)=>{const da=(a[0]-pommel[0])**2+(a[1]-pommel[1])**2+(a[2]-pommel[2])**2;const db=(b[0]-pommel[0])**2+(b[1]-pommel[1])**2+(b[2]-pommel[2])**2;return db>da?b:a;});
  return{wrist,shoulder,pommel,grip,tip};
}
const dist=(a,b)=>Math.hypot(a[0]-b[0],a[1]-b[1],a[2]-b[2]);
const fmt=v=>`(${v[0].toFixed(2)},${v[1].toFixed(2)},${v[2].toFixed(2)})`;

const armIdle=sampleAnim(idle,'state.land.idle',0);
const stillA=sampleAnim(still,'3rd_still',0);
const S=keyPts(Object.assign({},armIdle,stillA));
console.log('=== 静止 (3rd_still@0 + idle@0) ===');
console.log('  wrist =',fmt(S.wrist),' shoulder =',fmt(S.shoulder));
console.log('  pommel=',fmt(S.pommel),' grip =',fmt(S.grip),' tip =',fmt(S.tip));
console.log('  |wrist-pommel| =',dist(S.wrist,S.pommel).toFixed(3),'px   |wrist-grip| =',dist(S.wrist,S.grip).toFixed(3),'px');
console.log('  pommel-wrist 向量 =',fmt(V.sub(S.pommel,S.wrist)));

console.log('\n=== 挥砍全程 (3rd_attack@t，右臂静止 idle@0) ===');
console.log('  t      wrist            pommel           |wrist-pommel|  pommel-wrist向量');
const N=21; const rows=[];
for(let i=0;i<N;i++){const t=0.5*i/(N-1);const anims=Object.assign({},armIdle,sampleAnim(attack,'3rd_attack',t));const k=keyPts(anims);rows.push({t,k});console.log(`  ${t.toFixed(3)}  ${fmt(k.wrist)}  ${fmt(k.pommel)}  ${dist(k.wrist,k.pommel).toFixed(3).padStart(8)}  ${fmt(V.sub(k.pommel,k.wrist))}`);}
// 漂移统计
const drifts=rows.map(r=>dist(r.k.wrist,S.wrist)); // wrist 相对静止 wrist 的漂移（应为 0，臂静止）
const pomDrift=rows.map(r=>dist(r.k.pommel,S.pommel)); // pommel 相对静止 pommel 的漂移
console.log('\n  [统计] wrist 漂移 max =',Math.max(...drifts).toFixed(3),'px (右臂静止，预期≈0)');
console.log('  [统计] pommel 相对静止 pommel 漂移 max =',Math.max(...pomDrift).toFixed(3),'px');
const wp=rows.map(r=>dist(r.k.wrist,r.k.pommel));
console.log('  [统计] 挥砍中 |wrist-pommel| 范围 =',Math.min(...wp).toFixed(3),'~',Math.max(...wp).toFixed(3),'px (静止时 =',dist(S.wrist,S.pommel).toFixed(3),')');
