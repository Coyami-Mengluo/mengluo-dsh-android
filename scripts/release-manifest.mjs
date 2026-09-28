import {readFileSync,writeFileSync,copyFileSync,mkdirSync,statSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
const root=resolve(import.meta.dirname,'..'), version=process.argv[2], code=Number(process.argv[3]);
if(!/^\d+\.\d+\.\d+$/.test(version??'')||!Number.isSafeInteger(code)||code<2)throw Error('Provide release version and positive incremented versionCode');
const out=resolve(root,'dist',version);mkdirSync(out,{recursive:true});
const files={},sums=[];
for(const abi of ['arm64-v8a','x86_64']) {
 const metadata=JSON.parse(readFileSync(resolve(root,`dist/staging/${abi}/output-metadata.json`),'utf8'));
 if(metadata.applicationId!=='ai.mengluo.dsh.android'||metadata.elements.length!==1||metadata.elements[0].versionCode!==code||metadata.elements[0].versionName!==version)throw Error('APK build version mismatch');
 const file=resolve(root,`dist/staging/${abi}/app-release.apk`),name=`MengLuo-DSH-Android-${version}-${abi}.apk`;
 copyFileSync(file,resolve(out,name));const sha256=createHash('sha256').update(readFileSync(file)).digest('hex');
 files[abi]={url:`https://github.com/Coyami-Mengluo/mengluo-dsh-android/releases/download/v${version}/${name}`,sha256,size:statSync(file).size};sums.push(`${sha256}  ${name}`);
}
const log=readFileSync(resolve(root,'CHANGELOG.md'),'utf8');
const notes=log.split(`## ${version}\n`)[1]?.split('\n## ')[0]?.trim();
if(!notes)throw Error('Missing version section in CHANGELOG.md');
writeFileSync(resolve(out,'android-update.json'),JSON.stringify({schema:1,applicationId:'ai.mengluo.dsh.android',version,versionCode:code,minSdk:30,notes,files},null,2)+'\n');
copyFileSync(resolve(root,'dist/native-corresponding-source.tar.gz'),resolve(out,'native-corresponding-source.tar.gz'));
for(const name of ['android-update.json','native-corresponding-source.tar.gz'])sums.push(`${createHash('sha256').update(readFileSync(resolve(out,name))).digest('hex')}  ${name}`);
writeFileSync(resolve(out,'SHA256SUMS.txt'),sums.join('\n')+'\n');console.log(JSON.stringify({version,code,files},null,2));
