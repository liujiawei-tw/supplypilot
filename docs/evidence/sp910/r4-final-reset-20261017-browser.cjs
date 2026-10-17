'use strict';
const fs = require('node:fs');
const path = require('node:path');
const {createRequire} = require('node:module');
const runtimeRequire=createRequire('C:/Users/z0976/AppData/Local/OpenAI/Codex/runtimes/cua_node/a5a02988362ca169/bin/runner.cjs');
const {chromium}=runtimeRequire('playwright');
const BASE='http://127.0.0.1:5174';
const EVIDENCE=process.env.SP910_EVIDENCE_DIR;
const PROFILE=process.env.SP910_EDGE_PROFILE;
const PASSWORD=process.env.SP910_DEMO_PASSWORD;
const PHASE=process.argv[2];
if(!['admin','query','query-followup'].includes(PHASE)) throw new Error('Expected phase admin or query');
if(!PASSWORD) throw new Error('Secure Demo password is unavailable');
if(!fs.existsSync(EVIDENCE)) throw new Error('Evidence directory is missing');
fs.mkdirSync(PROFILE,{recursive:true});
(async()=>{
  const context=await chromium.launchPersistentContext(PROFILE,{channel:'msedge',headless:false,viewport:{width:1440,height:1000},args:['--no-first-run','--disable-sync']});
  const page=context.pages()[0]||await context.newPage();
  page.setDefaultTimeout(20000);
  const proof={phase:PHASE,startedAt:new Date().toISOString(),timezone:'Asia/Taipei',browser:'Microsoft Edge headed via Playwright launchPersistentContext',baseUrl:BASE,profile:'isolated r4 E9 profile',requests:[],failedRequests:[],console:[],screenshots:[],actions:[]};
  const apiOrigin='http://127.0.0.1:8184';
  const isApi=url=>url.origin===apiOrigin&&url.pathname.startsWith('/api/v1/');
  const waitResponse=(test,timeout=180000)=>page.waitForResponse(r=>{try{return test(new URL(r.url()),r.request().method())}catch{return false}},{timeout});
  const bodyOf=async response=>{try{return await response.json()}catch{return null}};
  async function shot(name){const target=path.join(EVIDENCE,name);await page.screenshot({path:target,fullPage:true});proof.screenshots.push(name);}
  page.on('response',r=>{try{const u=new URL(r.url());if(isApi(u))proof.requests.push({method:r.request().method(),path:u.pathname,status:r.status()});}catch{}});
  page.on('requestfailed',r=>{try{const u=new URL(r.url());proof.failedRequests.push({method:r.method(),path:u.pathname,failure:r.failure()?.errorText??null});}catch{}});
  page.on('console',m=>proof.console.push({type:m.type(),text:m.text().replace(/AIza[0-9A-Za-z_-]{20,}/g,'[REDACTED]').replace(/Bearer\s+[A-Za-z0-9._~+\/-]{12,}/gi,'Bearer [REDACTED]')}));
  async function login(){
    await page.goto(BASE+'/login',{waitUntil:'domcontentloaded'});
    await Promise.race([page.locator('form').waitFor({state:'visible'}),page.getByRole('heading',{name:'工作總覽',exact:true}).waitFor({state:'visible'})]);
    if(new URL(page.url()).pathname==='/login'&&await page.locator('form').isVisible().catch(()=>false)){
      await page.getByLabel('帳號').fill('admin');
      await page.getByLabel('密碼').fill(PASSWORD);
      await shot('login-page-before-submit.png');
      const submit=page.locator('form button[type="submit"]');
      await submit.waitFor({state:'visible'});
      if(!(await submit.isEnabled()))throw new Error('Login submit stayed disabled after form fields were filled');
      let responsePromise;
      try{
        responsePromise=waitResponse((u,m)=>u.pathname==='/api/v1/auth/login'&&m==='POST',45000);
        await submit.click();
        const response=await responsePromise;const body=await bodyOf(response);
        proof.login={httpStatus:response.status(),account:'admin',status:body?.status??null,secretStored:false,locator:'form button[type="submit"]'};
        if(response.status()!==200)throw new Error('Admin login did not return HTTP 200');
      }catch(error){if(responsePromise)await responsePromise.catch(()=>null);throw error;}
      await page.waitForURL(/\/dashboard(?:\?|$)/,{timeout:30000});
    }
    proof.currentUrl=page.url();
    proof.dashboardVisible=await page.getByRole('heading',{name:'工作總覽',exact:true}).isVisible().catch(()=>false);
    proof.adminIdentityVisible=await page.getByText('系統管理員',{exact:true}).count().then(n=>n>0);
    if(!proof.dashboardVisible||!proof.adminIdentityVisible)throw new Error('Admin Dashboard heading or role identity not visible after authentication');
    await shot(PHASE==='admin'?'admin-dashboard.png':'query-admin-dashboard.png');
  }
  async function openDocuments(){
    const link=page.getByRole('link',{name:'文件管理',exact:true});
    const menu=page.getByRole('menuitem',{name:/文件管理/});
    if(await link.count())await link.click();
    else if(await menu.count())await menu.click();
    else await page.goto(BASE+'/documents',{waitUntil:'domcontentloaded'});
    await page.getByRole('heading',{name:'文件管理',exact:true}).waitFor({state:'visible',timeout:30000});
    const pending=waitResponse((u,m)=>u.pathname==='/api/v1/documents'&&m==='GET',30000);
    await page.getByRole('button',{name:'重新載入',exact:true}).click();
    const response=await pending;const body=await bodyOf(response);
    proof.documentList={httpStatus:response.status(),total:body?.totalElements??null,documents:(body?.content||[]).map(d=>({id:d.id,title:d.title,documentType:d.documentType,supplierId:d.supplierId,version:d.version,status:d.status,storagePath:d.storagePath}))};
    await shot(PHASE==='admin'?'admin-documents-before-approval.png':'query-documents.png');
    return proof.documentList.documents;
  }
  try{
    await login();
    if(PHASE==='admin'){
      const docs=await openDocuments();
      const expected=[{id:12,title:'SUP-001 交付與責任協議'},{id:13,title:'SUP-002 虛構報價條件'},{id:14,title:'SupplyPilot 虛構展示採購作業程序'},{id:15,title:'MAT-001 阿薩姆紅茶萃取物規格'},{id:16,title:'虛構展示原料品質要求'}];
      if(docs.length!==5||expected.some(e=>!docs.some(d=>d.id===e.id&&d.title===e.title&&d.version==='1.0'&&d.status==='READY')))throw new Error('Browser document metadata does not match the five verified reset documents');
      proof.approvals=[];
      for(const d of expected){
        await page.getByRole('button',{name:`#${d.id}`,exact:true}).click();
        await page.getByRole('button',{name:'核准出站',exact:true}).waitFor({state:'visible'});
        const text=await page.locator('body').innerText();
        if(!text.includes(d.title)||!text.includes('已就緒'))throw new Error(`Expected READY document details not visible for ${d.id}`);
        await page.getByRole('button',{name:'核准出站',exact:true}).click();
        await page.getByRole('button',{name:'確認核准出站',exact:true}).waitFor({state:'visible'});
        const pending=waitResponse((u,m)=>u.pathname===`/api/v1/semantic-index/documents/${d.id}/approve-outbound`&&m==='POST',60000);
        await page.getByRole('button',{name:'確認核准出站',exact:true}).click();
        const response=await pending;const body=await bodyOf(response);
        const record={documentId:body?.documentId??d.id,httpStatus:response.status(),version:body?.version??null,sourceHash:body?.sourceHash??null,approvedBy:body?.approvedBy??null,status:body?.status??null,reason:body?.reason??null};
        proof.approvals.push(record);
        if(response.status()!==200||record.status!=='APPROVED'||record.version!=='1.0'||!record.sourceHash||!record.approvedBy)throw new Error(`Outbound approval was not APPROVED for document ${d.id}`);
        await page.getByText('本次審核回應：已核准',{exact:false}).waitFor({state:'visible',timeout:10000});
        await shot(`outbound-approved-${d.id}.png`);
        await page.keyboard.press('Escape');
      }
      const batches=[];let afterId=0;let more=true;let bound=0;
      while(more&&bound<5){
        bound++;
        await page.getByLabel('回填起始編號').fill(String(afterId));
        await page.getByLabel('每批回填上限').fill('25');
        const pending=waitResponse((u,m)=>u.pathname==='/api/v1/semantic-index/backfill'&&m==='POST',300000);
        await page.getByRole('button',{name:'執行一批',exact:true}).click();
        const response=await pending;const batch=await bodyOf(response);
        const rec={httpStatus:response.status(),...batch};batches.push(rec);
        if(response.status()!==200||batch?.failed>0||batch?.requiresReview>0){proof.backfillBatches=batches;throw new Error('Real Gemini Backfill returned failure or requires review; stopped for diagnosis');}
        more=Boolean(batch?.morePossible);afterId=Number(batch?.lastId??afterId);
        if(more)await page.getByRole('button',{name:new RegExp(`使用 lastId ${afterId} 作為下一批 afterId`)}).click();
      }
      if(more)throw new Error('Backfill exceeded the five-batch bound');
      proof.backfillBatches=batches;await shot('gemini-backfill-complete.png');
      const readinessPending=waitResponse((u,m)=>u.pathname==='/api/v1/semantic-index/readiness'&&m==='GET',60000);
      await page.getByRole('button',{name:'讀取目前就緒狀態',exact:true}).click();
      const readinessResponse=await readinessPending;proof.readiness={httpStatus:readinessResponse.status(),...(await bodyOf(readinessResponse))};
      if(readinessResponse.status()!==200)throw new Error('Readiness request failed');
      await shot('semantic-readiness.png');
      await page.getByRole('button',{name:'執行並保存 Gate 稽核',exact:true}).click();
      await page.getByRole('button',{name:'確認並保存稽核結果',exact:true}).waitFor({state:'visible'});
      const gatePending=waitResponse((u,m)=>u.pathname==='/api/v1/semantic-index/gate-audit'&&m==='POST',60000);
      await page.getByRole('button',{name:'確認並保存稽核結果',exact:true}).click();
      const gateResponse=await gatePending;proof.gateAudit={httpStatus:gateResponse.status(),...(await bodyOf(gateResponse))};
      if(gateResponse.status()!==200||proof.gateAudit.passed!==true||!proof.gateAudit.auditId)throw new Error('Persisted Semantic Gate Audit did not pass');
      await page.getByText('本次 Gate 稽核通過',{exact:true}).waitFor({state:'visible',timeout:15000});
      await shot('semantic-gate-audit-passed.png');
    } else {
      const docs=await openDocuments();proof.visibleDocumentCount=docs.length;
      const aiButton=page.getByRole('button',{name:/AI\s*助理/});
      if(await aiButton.count())await aiButton.first().click();
      else {const label=page.getByText('AI 助理',{exact:true});if(await label.count())await label.first().click();}
      const questionInput=page.getByRole('textbox',{name:'AI 問題'});
      await questionInput.waitFor({state:'visible',timeout:15000});
      const queries=[
        {caseId:'SUP-001-leadtime',question:'SUP-001 的 MAT-001 標準交期是多久？',terms:['5','曆日'],documentId:12,sample:'supplier-contract-sup-001-v1.txt'},
        {caseId:'SUP-002-validity',question:'SUP-002 的報價有效期限為多久？',terms:['30','曆日'],documentId:13,sample:'quotation-sup-002-v1.txt'},
        {caseId:'MAT-001-storage',question:'MAT-001 的基本單位及儲存條件為何？',terms:['KG','陰涼乾燥','陽光直射'],documentId:15,sample:'product-specification-mat-001-v1.txt'},
        {caseId:'SOP-receiving-role',question:'SupplyPilot 採購 SOP 由哪個角色執行收貨？',terms:['倉庫人員'],documentId:14,sample:'procurement-sop-v1.txt'},
        {caseId:'quality-quarantine',question:'哪些情況需要隔離批次？',terms:['缺少批次識別','封條破損','污染','物料不符'],documentId:16,sample:'quality-requirement-v1.txt'},
        {caseId:'insurance-insufficient',question:'這些文件是否規定冷凍海運的保險費率？',expectedStatus:'INSUFFICIENT_EVIDENCE'}
      ];
      if(PHASE==='query-followup')queries.shift();
      proof.queries=[];
      for(let i=0;i<queries.length;i++){
        const q=queries[i];const started=Date.now();await questionInput.fill(q.question);
        const pending=waitResponse((u,m)=>u.pathname==='/api/v1/ai/query'&&m==='POST',180000);
        await page.getByRole('button',{name:'送出問題',exact:true}).click();
        const response=await pending;const body=await bodyOf(response);
        const sources=(Array.isArray(body?.sources)?body.sources:[]).map(s=>({resourceType:s.resourceType,resourceId:s.resourceId,version:s.version,chunkIndex:s.chunkIndex,startOffset:s.startOffset,endOffset:s.endOffset,sourceHash:s.sourceHash,excerpt:s.excerpt}));
        const answer=typeof body?.answer==='string'?body.answer:'';
        const record={caseId:q.caseId,question:q.question,httpStatus:response.status(),status:body?.status??null,answer,answerSource:body?.answerSource??null,tools:Array.isArray(body?.tools)?body.tools.map(t=>typeof t==='string'?t:t?.name).filter(Boolean):[],traceId:body?.traceId??null,sources,elapsedMs:Date.now()-started,sourceValidation:[]};
        if(q.expectedStatus){record.expectedStatusPass=record.httpStatus===200&&record.status===q.expectedStatus&&sources.length===0;}
        else{
          const sourceSamples={'12':'supplier-contract-sup-001-v1.txt','13':'quotation-sup-002-v1.txt','14':'procurement-sop-v1.txt','15':'product-specification-mat-001-v1.txt','16':'quality-requirement-v1.txt'};
          record.sourceValidation=sources.map(s=>{
            const sourceSample=sourceSamples[String(s.resourceId)];
            const sample=sourceSample?fs.readFileSync(path.join(process.cwd(),'samples','documents',sourceSample),'utf8').replace(/\u0000/g,' ').replace(/[\t\x0B\f\r ]+/g,' ').replace(/ *\n+ */g,'\n').trim():'';
            const structurallyValid=Boolean(sourceSample)&&s.resourceType==='DOCUMENT'&&s.version==='1.0'&&Number.isInteger(s.startOffset)&&Number.isInteger(s.endOffset)&&s.startOffset>=0&&s.endOffset<=sample.length;
            return {resourceId:s.resourceId,version:s.version,chunkIndex:s.chunkIndex,startOffset:s.startOffset,endOffset:s.endOffset,belongsToExpectedDocument:s.resourceType==='DOCUMENT'&&String(s.resourceId)===String(q.documentId),knownDemoDocument:Boolean(sourceSample),utf16ExcerptMatchesSample:structurallyValid&&sample.slice(s.startOffset,s.endOffset).trim()===s.excerpt};
          });
          record.expectedStatusPass=record.httpStatus===200&&record.status==='ANSWERED'&&q.terms.every(t=>answer.includes(t))&&record.sourceValidation.some(s=>s.belongsToExpectedDocument&&s.utf16ExcerptMatchesSample)&&record.sourceValidation.every(s=>s.knownDemoDocument&&s.utf16ExcerptMatchesSample);
        }
        proof.queries.push(record);await shot(`${q.caseId}.png`);
        if(PHASE==='query'&&i===0&&!record.expectedStatusPass){proof.completedAt=new Date().toISOString();fs.writeFileSync(path.join(EVIDENCE,'reset-final-query-results.json'),JSON.stringify(proof,null,2));console.log(JSON.stringify({phase:PHASE,coreSup001Failed:true,caseId:q.caseId,httpStatus:record.httpStatus,status:record.status,traceId:record.traceId,sourceCount:sources.length}));await context.close();process.exit(2);}
        if(response.status()!==200){proof.completedAt=new Date().toISOString();fs.writeFileSync(path.join(EVIDENCE,'reset-final-query-results.json'),JSON.stringify(proof,null,2));console.log(JSON.stringify({phase:PHASE,caseId:q.caseId,httpStatus:record.httpStatus,status:record.status,traceId:record.traceId}));await context.close();process.exit(2);}
      }
    }
    proof.completedAt=new Date().toISOString();
    const output=path.join(EVIDENCE,PHASE==='admin'?'admin-gate-results.json':PHASE==='query-followup'?'remaining-gemini-query-results.json':'reset-final-query-results.json');fs.writeFileSync(output,JSON.stringify(proof,null,2));
    console.log(JSON.stringify({phase:PHASE,startedAt:proof.startedAt,completedAt:proof.completedAt,login:proof.login??null,documentCount:proof.documentList?.total??null,approvals:proof.approvals?.map(a=>({documentId:a.documentId,httpStatus:a.httpStatus,status:a.status,approvedBy:a.approvedBy,sourceHash:a.sourceHash}))??null,backfill:proof.backfillBatches??null,readiness:proof.readiness??null,gateAudit:proof.gateAudit??null,queries:proof.queries?.map(q=>({caseId:q.caseId,httpStatus:q.httpStatus,status:q.status,expectedStatusPass:q.expectedStatusPass,traceId:q.traceId,sourceCount:q.sources?.length}))??null,evidence:output}));
    await context.close();
  }catch(error){
    proof.completedAt=new Date().toISOString();proof.safeError=String(error.message||error).replace(/SP910_DEMO_PASSWORD/g,'[REDACTED]');
    try{if(new URL(page.url()).pathname==='/login')await page.getByLabel('密碼').fill('');}catch{}
    try{proof.failureContext={url:page.url(),title:await page.title(),bodyText:(await page.locator('body').innerText()).slice(0,6000),inputs:await page.locator('input').evaluateAll(xs=>xs.map(x=>({type:x.type,ariaLabel:x.getAttribute('aria-label'),id:x.id,placeholder:x.placeholder,disabled:x.disabled}))),buttons:await page.locator('button').evaluateAll(xs=>xs.map(x=>({text:x.innerText,ariaLabel:x.getAttribute('aria-label'),type:x.type,disabled:x.disabled})))};await shot(`${PHASE}-failure.png`);}catch{}
    const output=path.join(EVIDENCE,PHASE==='admin'?'admin-gate-results.json':PHASE==='query-followup'?'remaining-gemini-query-results.json':'reset-final-query-results.json');fs.writeFileSync(output,JSON.stringify(proof,null,2));
    console.error(JSON.stringify({phase:PHASE,safeError:proof.safeError,completedAt:proof.completedAt,evidence:output}));
    await context.close().catch(()=>{});process.exitCode=1;
  }
})();
