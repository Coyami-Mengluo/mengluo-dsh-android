import {mkdirSync,cpSync,writeFileSync,readFileSync} from 'node:fs';
import {resolve,basename} from 'node:path';
import {createHash} from 'node:crypto';
import {download,root,run} from './download.mjs';
const snapshot='3797184e81b2ae6cb220c6f2562d63ec80b57f9f';
const sources=[
 ['https://codeload.github.com/termux/proot/zip/refs/tags/v5.1.107.95','proot-5.1.107.95.zip','dbb50381c2f0b5c342bdf3d3467d80c21d2a4677d9dadd14159fa3b32f11b319'],
 ['https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz','talloc-2.4.3.tar.gz','dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd'],
 ['https://codeload.github.com/termux/libandroid-shmem/tar.gz/refs/tags/v0.7','libandroid-shmem-0.7.tar.gz','1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867'],
 [`https://codeload.github.com/termux/termux-packages/tar.gz/${snapshot}`,`termux-packages-${snapshot}.tar.gz`,'c797565d1fca52a36adc224851a607cdcebada3fbbf0ee220c1acdd1e4c9a0af'],
];
const out=resolve(root,'.tools/corresponding-source');mkdirSync(out,{recursive:true});
const manifest=[];
for(const [url,name,sha256] of sources){
 const file=await download(url,name,sha256);cpSync(file,resolve(out,name));
 manifest.push({name,url,sha256:createHash('sha256').update(readFileSync(file)).digest('hex')});
}
cpSync(resolve(root,'docs/NATIVE-SOURCES.md'),resolve(out,'README.md'));
for(const name of ['runtime-lock.json','runtime-lock.arm64-v8a.json'])cpSync(resolve(root,name),resolve(out,name));
writeFileSync(resolve(out,'sources.json'),JSON.stringify(manifest,null,2)+'\n');
mkdirSync(resolve(root,'dist'),{recursive:true});
run('tar.exe',['-czf',resolve(root,'dist/native-corresponding-source.tar.gz'),'-C',out,'.']);
console.log(JSON.stringify(manifest,null,2));
