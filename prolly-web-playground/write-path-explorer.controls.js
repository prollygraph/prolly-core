/*
 * write-path-explorer.controls.js — wiring: build/read/write/scan/seek
 * handlers, batch editor, branch/tag/merge controls, integrity lab, packs,
 * the writer race, and GC. Depends on core+state+render.
 */
/* ---------- wiring ---------- */
function freshBuild(){
  idbClear(CHUNKS); idbClear(META);
  badHashes=new Set();
  POOL.clear(); naiveBytes=0; commits=[]; selected=-1; selectedKey=null; pickedChunk=null;
  branches=new Map(); tags=new Map(); currentBranch=null;
  traceLog=[]; const rb=el('readbar'); if(rb){ rb.hidden=true; renderTraceLog(); }
  const n=+el('nkeys').value;
  const entries=Array.from({length:n},(_,i)=>({k:(i+1)*10, v:1}));
  commitOp(n>0? `build n=${n}` : 'genesis: empty', entries, -1, null);
  branches.set('main', 0); currentBranch='main';
  renderStrip(); renderBranchBar();
  simInspectMsg('click any chunk — a tree node, a ribbon box, a lens bar, or a store tile — and its identity, provenance, and contents land here.');
}
el('nkeys').oninput=()=>{ el('nval').textContent=el('nkeys').value; };
el('avg').onchange=renderCfgHint;
el('seed').addEventListener('input',renderCfgHint);
el('rebuild').onclick=freshBuild;
el('startempty').onclick=()=>{ el('nkeys').value=0; el('nval').textContent='0'; freshBuild(); };
el('insert').onclick=()=>{ const v=parseInt(el('keyin').value,10);
  if(Number.isFinite(v)) { doInsert(v); el('keyin').value=''; } else hint('type an integer key first'); };
el('keyin').addEventListener('keydown',e=>{ if(e.key==='Enter') el('insert').click(); });
el('update').onclick=()=>{ if(selectedKey!=null) doUpdate(selectedKey); };
el('delete').onclick=()=>{ if(selectedKey!=null) doDelete(selectedKey); };
let rngCounter=1;
el('randop').onclick=()=>randomOp(mulberry(fnv('rop'+(rngCounter++)+el('seed').value)));
el('batchtoggle').onclick=()=>{
  const bp=el('batchpanel'); bp.hidden=!bp.hidden;
  if(!bp.hidden){ el('batchtext').focus(); refreshBatchSummary(); }
};
el('batchclose').onclick=()=>{ el('batchpanel').hidden=true; };

function refreshBatchSummary(){
  const r=parseBatch(el('batchtext').value, cur().entries);
  const sm=el('batchsummary');
  if(!r.ops.length && !r.errors.length){
    sm.innerHTML='one op per line — staged in order (read-your-own-writes), landed as ONE commit. Ctrl+Enter commits.';
    el('batchcommit').disabled=true; return;
  }
  if(r.errors.length){
    sm.innerHTML=`<span class="berr">${r.errors.length} error${r.errors.length===1?'':'s'} — the batch is atomic, nothing lands: ${r.errors[0]}</span>`;
    el('batchcommit').disabled=true; return;
  }
  sm.innerHTML=`<span class="bok">staged: ${r.ops.length} op${r.ops.length===1?'':'s'} — ${r.ins} insert · ${r.upd} update · ${r.del} delete`+
    (r.dups.length? ` · keys ${r.dups.join(', ')} touched twice (last line wins)`:'')+`</span>`;
  el('batchcommit').disabled=false;
}
el('batchtext').addEventListener('input',refreshBatchSummary);
el('batchtext').addEventListener('keydown',e=>{
  if(e.key==='Enter' && (e.ctrlKey||e.metaKey)){ e.preventDefault(); el('batchcommit').click(); }
});

el('batchcommit').onclick=()=>{
  const r=parseBatch(el('batchtext').value, cur().entries);
  if(r.errors.length || !r.ops.length) return;
  const map=new Map(cur().entries.map(e=>[e.k,e.v]));
  const touched=new Set();
  r.ops.forEach(op=>{ touched.add(op.k);
    if(op.op==='del') map.delete(op.k); else map.set(op.k,op.v); });
  const es=[...map.entries()].map(([k,v])=>({k,v})).sort((a,b)=>a.k-b.k);
  commitOp(`batch ×${r.ops.length} (${r.ins}i·${r.upd}u·${r.del}d)`, es, selected, null, {touchedKeys:touched});
  el('batchtext').value=''; refreshBatchSummary();
};

el('batchfill').onclick=()=>{
  const es=baseEntries();
  const rng=mulberry(fnv('batch'+(rngCounter++)+el('seed').value));
  const lines=[];
  for(let t=0;t<5;t++){
    const roll=rng();
    if(roll<0.6 || es.length<6){
      for(let a=0;a<30;a++){
        if(es.length<2){ const k=50+Math.floor(rng()*100);
          if(!es.some(e=>e.k===k)){ es.push({k,v:1}); es.sort((x,y)=>x.k-y.k); lines.push(`${k}`); break; } continue; }
        const i=Math.floor(rng()*(es.length-1));
        const g1=es[i].k, g2=es[i+1].k;
        if(g2-g1>1){ const k=g1+1+Math.floor(rng()*(g2-g1-1));
          if(!es.some(e=>e.k===k)){ es.push({k,v:1}); es.sort((x,y)=>x.k-y.k); lines.push(`${k}`); break; } }
      }
    } else if(roll<0.85){ const e=es[Math.floor(rng()*es.length)]; e.v++; lines.push(`${e.k}=${e.v}`); }
    else if(es.length>6){ const i=Math.floor(rng()*es.length); lines.push(`-${es[i].k}`); es.splice(i,1); }
  }
  el('batchpanel').hidden=false;
  el('batchtext').value=lines.join('\n');
  refreshBatchSummary();
};
el('stress').onclick=()=>{
  animWrites=false;
  try{ for(let i=0;i<10;i++) randomOp(mulberry(fnv('st'+(rngCounter++)+el('seed').value))); }
  finally{ animWrites=true; }
};
el('converge').onclick=()=>{
  const c=cur();
  if(!c.entries.length){
    el('simnarr').innerHTML='<b>convergence</b>: the empty tree is trivially convergent — insert something first.';
    return;
  }
  const rng=mulberry(fnv('conv|'+(rngCounter++)));
  const order=[...c.entries];
  for(let i=order.length-1;i>0;i--){ const j=Math.floor(rng()*(i+1)); [order[i],order[j]]=[order[j],order[i]]; }
  // scratch replica: build incrementally in the shuffled order, then remove any
  // chunk it minted that the real store didn't already hold (content addressing
  // makes the final chunks collide with the real ones if — and only if — the
  // replica converged).
  const before=new Set(POOL.keys());
  let acc=[], root=null;
  for(const e of order){ acc.push({...e}); acc.sort((a,b)=>a.k-b.k); root=buildTree(acc, c.cfg.avg, c.cfg.seed); }
  for(const h of [...POOL.keys()]) if(!before.has(h)) POOL.delete(h);
  const same = root===c.rootHash;
  el('simnarr').innerHTML = same
    ? `<b>convergence ✓</b> an independent replica inserted the same ${order.length} keys in a random order, one commit at a time — its final root <b>${root.slice(0,10)}</b> is byte-identical to this commit's. History-independence is why diff, merge, and sync work at all.`
    : `<b>convergence FAILED</b> — replica root ${root? root.slice(0,10):'∅'} ≠ ${c.rootHash.slice(0,10)}. That would be a model bug; please report it.`;
};

function cancelReadAnim(){
  if(readAnim){
    if(readAnim.timer) clearInterval(readAnim.timer);
    if(readAnim.group && readAnim.pos<readAnim.steps.length-1){
      readAnim.group.rows.push('— interrupted (a new operation took over)');
      readAnim.group.verdict='interrupted';
    }
  }
  readAnim=null;
  d3.selectAll('circle.cursor-dot').remove();
  const rc=el('readcaption'); if(rc) rc.hidden=true;
  document.querySelectorAll('#ribbon .keycell.scanflash').forEach(e2=>e2.classList.remove('scanflash'));
}

/** Render the accumulated journal: every past trace as a titled group, the live
    trace's newest row highlighted. The log persists across operations so runs
    can be compared side by side; only 'clear' (or Rebuild) empties it. */
function renderTraceLog(){
  const log=el('rlog'); if(!log) return;
  const jc=el('jcount');
  if(jc) jc.textContent= traceLog.length? `${traceLog.length} trace${traceLog.length===1?'':'s'}` : 'empty';
  if(!traceLog.length){
    log.innerHTML='<tr><td class="fhint">no traces yet — every read, write, diff, merge, and seek logs its algorithm here</td></tr>';
    return;
  }
  const parts=[];
  traceLog.forEach(gr=>{
    parts.push(`<tr class="rgroup"><td>${gr.title}${gr.verdict? ' — '+gr.verdict:''}</td></tr>`);
    const live = readAnim && readAnim.group===gr;
    gr.rows.forEach((r,i)=>parts.push(`<tr class="${live&&i===gr.rows.length-1?'rcur':''}"><td>${r}</td></tr>`));
  });
  log.innerHTML=parts.join('');
  const curRow=log.querySelector('tr.rcur')||log.lastElementChild;
  if(curRow) curRow.scrollIntoView({block:'nearest'});
}
function newTraceGroup(title){
  const gr={title, rows:[], verdict:''};
  traceLog.push(gr);
  if(traceLog.length>30) traceLog.shift(); // bound the journal
  return gr;
}

/** Flatten the read into ALGORITHM-GRANULARITY steps: every node fetch, every
    child maxKey comparison (the seek rule, one compare at a time), every leaf
    scan compare, then the verdict. This is what the cursor animation walks. */
function buildReadSteps(res, k){
  const steps=[]; let fetchNo=0;
  const ord=n=>['1st','2nd','3rd'][n-1]||n+'th';
  res.steps.forEach(st=>{
    fetchNo++;
    if(st.corrupt){
      steps.push({type:'fetch', node:st.node,
        txt:`fetch ${st.node.hash.slice(0,10)} — ${ord(fetchNo)} store read…`});
      steps.push({type:'stop', node:st.node,
        txt:`re-hash: the bytes hash to ${st.actual.slice(0,10)}, but the NAME says ${st.node.hash.slice(0,10)} → CORRUPTION DETECTED — the read halts (ProllyCorruptionException); no external truth was needed`});
      return;
    }
    steps.push({type:'fetch', node:st.node,
      txt:`fetch ${st.node.hash.slice(0,10)} — ${st.leaf?'leaf':'L'+st.node.level} node, ${ord(fetchNo)} store read · re-hashed ✓`});
    if(!st.leaf){
      for(let i=0;i<st.node.children.length;i++){
        const kid=POOL.get(st.node.children[i]);
        const mx=kid? kid.span[1] : null;
        const hit=kid && mx>=k;
        steps.push({type:'cmp', node:st.node, childHash:st.node.children[i],
          txt: hit? `compare: k=${k} ≤ maxKey ${mx} of child ${i+1} → descend ↓`
                  : `compare: k=${k} > maxKey ${mx} of child ${i+1} → skip right`});
        if(hit) break;
      }
      if(st.chosen<0) steps.push({type:'stop', node:st.node,
        txt:`k=${k} > every maxKey (${st.node.span[1]}) → STOP: not in this tree, no descent needed`});
    } else {
      for(const e of st.node.entries){
        if(e.k<k){ steps.push({type:'scan', node:st.node, ek:e.k, txt:`leaf scan: ${e.k} < ${k} → next entry`}); continue; }
        if(e.k===k){ steps.push({type:'scan', node:st.node, ek:e.k, txt:`leaf scan: ${e.k} == ${k} ✓ match`}); }
        else { steps.push({type:'scan', node:st.node, ek:e.k, txt:`leaf scan: ${e.k} > ${k} → passed the slot: key absent`}); }
        break;
      }
      steps.push({type:'verdict', node:st.node,
        txt: st.entry? `read the value: v${st.entry.v}` : `NOT FOUND — the sorted scan proved absence`});
    }
  });
  return steps;
}

function applyStepsUpTo(pos){
  const A=readAnim; if(!A) return;
  A.pos=Math.max(0, Math.min(pos, A.steps.length-1));
  const visited=new Set();
  A.group.rows=[];
  for(let i=0;i<=A.pos;i++){
    const s1=A.steps[i];
    if(s1.type==='fetch') visited.add(s1.node.hash);
    A.group.rows.push(s1.txt);
  }
  const svg=d3.select('#aftersvg');
  svg.selectAll('g.nd').classed('cmping',false).classed('cursorat',false);
  const st=A.steps[A.pos];
  if(A.mode==='read' || A.mode==='seek' || A.mode==='scan'){
    readPath={commit:A.commit, set:visited};
    svg.selectAll('g.nd')
      .classed('onread',function(){ return visited.has(this.getAttribute('data-hash')); });
  } else if(A.mode==='write'){
    // write mode: progressive reveal — a written chunk stays veiled until its
    // wbuild step emits it
    const built=new Set();
    for(let i=0;i<=A.pos;i++){ const s2=A.steps[i];
      if((s2.type==='wbuild'||s2.type==='wroot') && s2.node) built.add(s2.node.hash); }
    svg.selectAll('g.nd').classed('notyet',function(){
      const h=this.getAttribute('data-hash');
      return A.written.has(h) && !built.has(h);
    });
  }
  if(st.hashes){
    const hs=new Set(st.hashes);
    svg.selectAll('g.nd').classed('cmping',function(){ return hs.has(this.getAttribute('data-hash')); });
  }
  if(st.node) svg.selectAll('g.nd').filter(function(){ return this.getAttribute('data-hash')===st.node.hash; })
    .classed('cursorat',true);
  if(st.type==='cmp'){
    svg.selectAll('g.nd').filter(function(){ return this.getAttribute('data-hash')===st.childHash; })
      .classed('cmping',true);
  }
  const at=st.node? afterNodePos.get(st.node.hash) : null;
  if(at){
    const holder=svg.select('g').select('g');
    let dot=holder.select('circle.cursor-dot');
    if(dot.empty()) dot=holder.append('circle').attr('class','cursor-dot').attr('r',5)
      .attr('cx',at.x+NW/2).attr('cy',at.y+NH/2);
    (reduced? dot : dot.transition().duration(300)).attr('cx',at.x+NW/2).attr('cy',at.y+NH/2);
  }
  document.querySelectorAll('#ribbon .keycell.scanflash').forEach(e2=>e2.classList.remove('scanflash'));
  if(st.ek!=null){
    const kc=document.querySelector(`#ribbon .keycell[data-k="${st.ek}"]`);
    if(kc) kc.classList.add('scanflash');
  }
  if(st.type==='wbuild' && st.node && st.node.level===0){
    const cb=document.querySelector(`#ribbon .chunkbox[data-hash="${st.node.hash}"]`);
    if(cb){ cb.classList.remove('flashit'); void cb.offsetWidth; cb.classList.add('flashit'); }
  }
  el('rstatus').textContent=`step ${A.pos+1}/${A.steps.length}`;
  const capEl=el('readcaption');
  capEl.hidden=false;
  capEl.textContent=`step ${A.pos+1}: ${st.txt}`;
  if(A.pos===A.steps.length-1){
    stopReadPlay();
    if(A.mode==='read'){
      A.group.verdict=A.res.corrupt? 'CORRUPTION DETECTED' : A.res.found? `FOUND v${A.res.entry.v}`:'NOT FOUND';
      el('rverdict').innerHTML=A.res.corrupt
        ? `<span class="iorph">CORRUPTION DETECTED</span>`
        : A.res.found
        ? `<span class="inew">FOUND v${A.res.entry.v}</span>` : `<span class="iorph">NOT FOUND</span>`;
      el('simnarr').innerHTML=`<b>read k=${A.k}</b> at ${cshort(A.commit)}: ${A.res.found?'FOUND v'+A.res.entry.v:'NOT FOUND'} `+
        `in ${A.res.steps.length} fetch${A.res.steps.length===1?'':'es'} — the cyan path is everything the lookup touched; `+
        `every other chunk stayed cold.`;
    } else if(A.mode==='write'){
      A.group.verdict=`+${A.written.size} chunks`;
      el('rverdict').innerHTML=`<span class="inew">+${A.written.size} chunks</span>`;
    } else if(A.mode==='cas'){
      A.group.verdict='linear ✓';
      el('rverdict').innerHTML=`<span class="inew">linear ✓ no fork</span>`;
    } else if(A.mode==='scan'){
      const r=A.scanRes;
      A.group.verdict= r.corrupt? 'CORRUPTION DETECTED' : `${r.results.length} keys`;
      el('rverdict').innerHTML= r.corrupt
        ? `<span class="iorph">CORRUPTION DETECTED</span>`
        : `<span class="inew">${r.results.length} key${r.results.length===1?'':'s'} · ${r.fetched.size} fetches</span>`;
    } else if(A.mode==='seek'){
      A.group.verdict=A.seekVerdict;
      el('rverdict').innerHTML=A.seekVerdict==='out of range'
        ? `<span class="iorph">out of range</span>` : `<span class="inew">${A.seekVerdict}</span>`;
    } else if(A.mode==='merge'){
      A.group.verdict=A.conflicts? `${A.conflicts} conflict${A.conflicts===1?'':'s'} resolved` : 'clean merge';
      el('rverdict').innerHTML=A.conflicts
        ? `<span class="iorph">${A.conflicts} conflict${A.conflicts===1?'':'s'} resolved</span>`
        : `<span class="inew">clean merge</span>`;
    } else {
      A.group.verdict=`Δ ${A.delta} keys`;
      el('rverdict').innerHTML=`<span class="ishared">Δ ${A.delta} keys</span>`;
    }
  } else {
    A.group.verdict='';
    el('rverdict').textContent='…';
  }
  renderTraceLog();
}

function stopReadPlay(){
  const A=readAnim; if(!A) return;
  if(A.timer){ clearInterval(A.timer); A.timer=null; }
  const b=el('rplay'); if(b) b.textContent='▶';
}
function startReadPlay(){
  const A=readAnim; if(!A || A.timer) return;
  if(A.pos>=A.steps.length-1) A.pos=-1; // replay from the top
  A.timer=setInterval(()=>{
    if(A.pos<A.steps.length-1) applyStepsUpTo(A.pos+1); else stopReadPlay();
  }, 450);
  const b=el('rplay'); if(b) b.textContent='⏸';
}

/** Per-level three-zone decomposition of a write, computed from the REAL trees:
    hash-equal prefix (skipped by reference), the rebuilt middle, hash-equal
    suffix (the resync). Content addressing makes hash equality exactly "zone". */
function buildWriteSteps(parent, c){
  const steps=[];
  steps.push({type:'wop', txt:`${c.label} — merge the edit into the sorted stream (base: ${cshort(parent.id)})`});
  if(!c.rootHash){
    steps.push({type:'wempty', txt:'last key removed — the tree is ∅; every old chunk is now orphaned'});
  } else {
    const rootLevel=POOL.get(c.rootHash).level;
    const oldRootLevel=parent.rootHash&&POOL.has(parent.rootHash)? POOL.get(parent.rootHash).level : -1;
    // L0: the REAL mechanism — replay the fast-forward walk (differential-pinned
    // to the build) when this is a plain tree write with a readable base
    const canFF = c.parent2==null && !parent.pruned;
    let Lstart=0;
    if(canFF){
      Lstart=1;
      const A=new Map(parent.entries.map(e=>[e.k,e.v]));
      const B=new Map(c.entries.map(e=>[e.k,e.v]));
      const edits=[];
      B.forEach((v,k)=>{ if(!A.has(k)||A.get(k)!==v) edits.push({k,v}); });
      A.forEach((v,k)=>{ if(!B.has(k)) edits.push({k,v:null}); });
      edits.sort((x,y)=>x.k-y.k);
      const ff=ffApply(parent.rootHash, edits, c.cfg.avg, c.cfg.seed);
      let run=[];
      const flush=()=>{
        if(!run.length) return;
        const keys=run.reduce((a,n)=>a+n.count,0);
        steps.push({type:'wskip', hashes:run.map(n=>n.hash),
          txt:`L0: fast-forward — skip ${run.length} leaf${run.length===1?'':'s'} (${keys} keys) by reference; the cursor never opens them`});
        run=[];
      };
      ff.steps.forEach(st=>{
        if(st.t==='skip'){ run.push(st.node); return; }
        flush();
        if(st.t==='open') steps.push({type:'wlevel',
          txt:`L0: open leaf ${st.node.hash.slice(0,10)} [${st.node.span[0]}‥${st.node.span[1]}] — merge the due edits, re-chunk through the splitter`});
        else if(st.t==='emit') steps.push({type:'wbuild', node:st.node,
          txt:`L0: emit ${st.node.hash.slice(0,10)} [${st.node.span[0]}‥${st.node.span[1]}] · ${st.node.count} keys`});
        else if(st.t==='resync') steps.push({type:'wlevel',
          txt:`L0: boundary aligned after ${st.k} — back in sync with the old run`});
      });
      flush();
    }
    for(let L=Lstart;L<=rootLevel;L++){
      const neu=lensLevelNodes(c.rootHash,L);
      if(L>oldRootLevel && oldRootLevel>=0){
        steps.push({type:'wlevel', txt:`L${L}: the tree grew a level — a new grouping is born above the old root`});
      }
      // run-based decomposition: consecutive written/shared runs — honest for
      // scattered batch edits, identical to prefix/suffix zones for one edit
      const runs=[]; let run=null;
      neu.forEach(n=>{ const w=c.written.has(n.hash);
        if(!run||run.w!==w){ run={w,nodes:[n]}; runs.push(run); } else run.nodes.push(n); });
      runs.forEach((r,ri)=>{
        if(!r.w){
          const tail=ri===runs.length-1 && ri>0;
          steps.push({type:'wskip', hashes:r.nodes.map(n=>n.hash),
            txt:`L${L}: ${r.nodes.length} chunk${r.nodes.length===1?'':'s'} `+
                (tail? 'after the last edit — boundary realigned, shared by reference'
                     : 'unchanged — identical hashes, skipped by reference')});
        } else {
          r.nodes.forEach(n=>steps.push({type:'wbuild', node:n,
            txt:`L${L}: ${L===0?'re-chunk':'child hash changed → rebuild'} → ${n.hash.slice(0,10)} [${n.span[0]}‥${n.span[1]}]`+
                (L<rootLevel?' · emit to parent':'')}));
        }
      });
    }
    if(oldRootLevel>rootLevel) steps.push({type:'wlevel', txt:`the tree SHRANK ${oldRootLevel-rootLevel} level(s) — fewer chunks need fewer groupings`});
    steps.push({type:'wroot', node:POOL.get(c.rootHash),
      txt:`new root ${c.rootHash.slice(0,10)} — the tree's identity; nothing has mutated yet`});
  }
  const orphaned=parent.rootHash? [...reach(parent.rootHash)].filter(h=>!reach(c.rootHash).has(h)).length : 0;
  steps.push({type:'wcommit', txt:`commit ${cshort(c.id)} minted (parent ${cshort(parent.id)}) — ${c.written.size} chunks written, ${orphaned} orphaned to the sweeper`});
  steps.push({type:'wmanifest', txt: c.ref
    ? `manifest: '${c.ref}' re-points → ${cshort(c.id)} — THE pointer swap, the only mutation in the whole system`
    : `no ref advanced — this commit is detached; use 'Branch here' to name it`});
  return steps;
}

/** The DiffEngine walk, honestly staged: descend the selected tree; any subtree
    whose hash exists in the base is skipped UNREAD (content addressing makes
    hash-equality subtree-equality). Cost is O(changed), never O(n). */
function buildDiffSteps(base, sel){
  const steps=[]; const rBase=reach(base.rootHash);
  let skippedKeys=0, skips=0;
  steps.push({type:'dsum', txt:`diff ${cshort(base.id)} → ${cshort(sel.id)}: walk the selected tree, skipping every subtree whose hash the base already has`});
  if(sel.rootHash){
    (function walk(h){
      const n=POOL.get(h); if(!n) return;
      if(rBase.has(h)){
        skippedKeys+=n.count; skips++;
        steps.push({type:'dskip', node:n, hashes:[h],
          txt:`${n.level===0?'leaf':'L'+n.level} ${h.slice(0,8)} [${n.span[0]}‥${n.span[1]}]: hash in base → skip ${n.count} key${n.count===1?'':'s'} UNREAD`});
        return;
      }
      if(n.children){
        steps.push({type:'ddesc', node:n, txt:`L${n.level} ${h.slice(0,8)}: hash differs → descend`});
        n.children.forEach(walk);
      } else {
        steps.push({type:'ddesc', node:n, txt:`leaf ${h.slice(0,8)} [${n.span[0]}‥${n.span[1]}]: changed — compare its entries`});
      }
    })(sel.rootHash);
  }
  const A=new Map(base.entries.map(e=>[e.k,e.v])), B=new Map(sel.entries.map(e=>[e.k,e.v]));
  const adds=[], dels=[], mods=[];
  B.forEach((v,k)=>{ if(!A.has(k)) adds.push(k); else if(A.get(k)!==v) mods.push([k,A.get(k),v]); });
  A.forEach((v,k)=>{ if(!B.has(k)) dels.push(k); });
  adds.sort((a,b)=>a-b).forEach(k=>steps.push({type:'dkey', ek:k, txt:`+ k=${k} added (v${B.get(k)})`}));
  dels.sort((a,b)=>a-b).forEach(k=>steps.push({type:'dkey', txt:`− k=${k} removed`}));
  mods.sort((a,b)=>a[0]-b[0]).forEach(([k,a,b2])=>steps.push({type:'dkey', ek:k, txt:`~ k=${k} changed v${a} → v${b2}`}));
  const delta=adds.length+dels.length+mods.length;
  steps.push({type:'dsum',
    txt:`Δ ${delta} key${delta===1?'':'s'} (${adds.length}+ ${dels.length}− ${mods.length}~) · ${skippedKeys} keys in ${skips} subtree${skips===1?'':'s'} skipped UNREAD — O(changed), not O(n)`});
  return {steps, delta};
}

function startDiffTrace(base, sel){
  cancelReadAnim();
  const d=buildDiffSteps(base, sel);
  readAnim={mode:'diff', steps:d.steps, pos:-1, res:null, k:null, commit:sel.id, written:null, timer:null,
            delta:d.delta, group:newTraceGroup(`DIFF ${cshort(base.id)} → ${cshort(sel.id)}`)};
  el('readbar').hidden=false;
  el('rtitle').textContent='diff walk';
  el('rstatus').textContent=''; el('rverdict').textContent='…';
  el('rplay').textContent='▶';
  el('rsummary').textContent=
    `${cshort(base.id)} vs ${cshort(sel.id)} · dashed-cyan flashes are subtrees skipped UNREAD by hash equality — the diff never opens them`;
  el('simnarr').innerHTML=`<b>diff ${cshort(base.id)} → ${cshort(sel.id)}</b> — the panes now color vs the pinned base; shift-click the base node again to unpin.`;
  if(reduced){ applyStepsUpTo(d.steps.length-1); }
  else { applyStepsUpTo(0); startReadPlay(); }
}

function startWriteTrace(c){
  cancelReadAnim();
  const parent=commits[c.parent];
  const steps=buildWriteSteps(parent, c);
  readAnim={mode:'write', steps, pos:-1, res:null, k:null, commit:c.id, written:c.written, timer:null,
            group:newTraceGroup(`WRITE ${c.label} → ${cshort(c.id)}`)};
  el('readbar').hidden=false;
  el('rtitle').textContent='write path';
  el('rstatus').textContent=''; el('rverdict').textContent='…';
  el('rplay').textContent='▶';
  el('rsummary').textContent=
    `${c.label} at ${cshort(c.id)} · wrote ${c.written.size} of ${c.total} chunks · watch the spine build bottom-up — veiled chunks haven't been emitted yet`;
  if(reduced){ applyStepsUpTo(steps.length-1); }
  else { applyStepsUpTo(0); startReadPlay(); }
}

function doRead(k){
  const c=cur();
  if(!c.rootHash){
    el('simnarr').innerHTML='<b>read</b>: the tree is empty — nothing to find, zero fetches.';
    return;
  }
  cancelReadAnim();
  const res=readTrace(c.rootHash, k);
  const steps=buildReadSteps(res, k);
  readAnim={mode:'read', steps, pos:-1, res, k, commit:c.id, written:null, timer:null,
            group:newTraceGroup(`READ k=${k} @ ${cshort(c.id)}`)};
  const total=reach(c.rootHash).size;
  const height=POOL.get(c.rootHash).level+1;
  el('readbar').hidden=false;
  el('rtitle').textContent='read path';
  el('rstatus').textContent=''; el('rverdict').textContent='…';
  el('rplay').textContent='▶';
  el('rsummary').textContent=
    `k=${k} at ${cshort(c.id)} · ${res.steps.length} node fetch${res.steps.length===1?'':'es'} · tree height ${height} · `+
    `${total-res.steps.length} of ${total} chunks never touched · every fetch is content-addressed — the root hash alone pins the snapshot`;
  el('simnarr').innerHTML=`<b>read k=${k}</b> at ${cshort(c.id)} — watch the cursor descend; pause and scrub with the step controls under the tree.`;
  if(reduced){ applyStepsUpTo(steps.length-1); }   // no motion: show the full algorithm log at once
  else { applyStepsUpTo(0); startReadPlay(); }
}
// stepper controls are static DOM — wired once, guarded on an active read
el('rprev').onclick=()=>{ if(!readAnim) return; stopReadPlay(); applyStepsUpTo(readAnim.pos-1); };
el('rnext').onclick=()=>{ if(!readAnim) return; stopReadPlay(); applyStepsUpTo(readAnim.pos+1); };
el('rend').onclick =()=>{ if(!readAnim) return; stopReadPlay(); applyStepsUpTo(readAnim.steps.length-1); };
el('rplay').onclick=()=>{ if(!readAnim) return; readAnim.timer? stopReadPlay() : startReadPlay(); };
el('rclose').onclick=()=>{ cancelReadAnim(); readPath=null; renderTrees(); el('readbar').hidden=true; };
el('rclear').onclick=()=>{
  cancelReadAnim(); traceLog=[]; renderTraceLog();
  el('rstatus').textContent=''; el('rverdict').textContent=''; el('rsummary').textContent='journal cleared';
};
/** Seek the Nth key using the subtree-count vector: each internal node knows how
    many keys live under every child, so the descent subtracts counts instead of
    reading data — ordinal queries in O(height), size queries in O(1). */
function doSeek(idx){
  const c=cur();
  if(!c.rootHash){ el('simnarr').innerHTML='<b>seek</b>: the tree is empty.'; return; }
  cancelReadAnim();
  const root=POOL.get(c.rootHash);
  const steps=[]; const ordTxt=i=>['1st','2nd','3rd'][i-1]||i+'th';
  let verdict='', entry=null;
  if(idx<1 || idx>root.count){
    steps.push({type:'fetch', node:root,
      txt:`fetch root ${c.rootHash.slice(0,10)} — it KNOWS the tree holds ${root.count} keys (the count vector)`});
    steps.push({type:'cmp', node:root,
      txt:`index ${idx} is out of range [1‥${root.count}] → answered in ONE fetch — size queries are O(1)`});
    verdict='out of range';
  } else {
    let h=c.rootHash, rem=idx, fetchNo=0;
    for(;;){
      const n=POOL.get(h); fetchNo++;
      steps.push({type:'fetch', node:n,
        txt:`fetch ${h.slice(0,10)} — ${n.level===0?'leaf':'L'+n.level}, ${ordTxt(fetchNo)} store read (holds ${n.count} keys)`});
      if(n.level===0){
        entry=n.entries[rem-1];
        steps.push({type:'scan', node:n, ek:entry.k,
          txt:`the ${ordTxt(rem)} entry of this leaf → k=${entry.k} = v${entry.v} ✓`});
        break;
      }
      // The routing reads ONLY this node's own counts vector — the children are
      // never fetched until the descent commits to one. (An earlier version of
      // this trace peeked at each child's count while CLAIMING O(height) — the
      // count vector is exactly what makes that claim true.)
      let picked=false;
      for(let i=0;i<n.counts.length;i++){
        if(rem>n.counts[i]){
          steps.push({type:'cmp', node:n, childHash:n.children[i],
            txt:`count vector[${i+1}] says child ${i+1} holds ${n.counts[i]} keys < remainder ${rem} → skip it whole (no fetch), remainder −${n.counts[i]} = ${rem-n.counts[i]}`});
          rem-=n.counts[i];
        } else {
          steps.push({type:'cmp', node:n, childHash:n.children[i],
            txt:`remainder ${rem} falls inside child ${i+1} (count vector says ${n.counts[i]} keys) → descend ↓`});
          h=n.children[i]; picked=true; break;
        }
      }
      if(!picked) break;
    }
    verdict=`k=${entry.k}`;
  }
  readAnim={mode:'seek', steps, pos:-1, res:null, k:null, commit:c.id, written:null, timer:null,
            seekVerdict:verdict, group:newTraceGroup(`SEEK ${ordTxt(idx)} key @ ${cshort(c.id)}`)};
  el('readbar').hidden=false;
  el('rtitle').textContent='ordinal seek';
  el('rstatus').textContent=''; el('rverdict').textContent='…';
  el('rplay').textContent='▶';
  el('rsummary').textContent=
    `seek the ${ordTxt(idx)} key at ${cshort(c.id)} · the count vector answers "how many keys below?" without reading a single entry`;
  el('simnarr').innerHTML=`<b>seek #${idx}</b> — the descent subtracts subtree counts; no data is read until the leaf.`;
  if(reduced){ applyStepsUpTo(steps.length-1); }
  else { applyStepsUpTo(0); startReadPlay(); }
}
el('idxbtn').onclick=()=>{
  const v=parseInt(el('idxin').value,10);
  if(Number.isFinite(v)) doSeek(v); else hint('type an index (1-based) to seek');
};
el('idxin').addEventListener('keydown',e=>{ if(e.key==='Enter') el('idxbtn').click(); });

function doScan(from, to){
  const c=cur();
  cancelReadAnim();
  const res=scanTrace(c.rootHash, from, to);
  const total=c.rootHash? reach(c.rootHash).size : 0;
  readAnim={mode:'scan', steps:res.steps, pos:-1, res:null, k:null, commit:c.id, written:null, timer:null,
            scanRes:res, group:newTraceGroup(`SCAN ${from}‥${to} @ ${cshort(c.id)}`)};
  el('readbar').hidden=false;
  el('rtitle').textContent='range scan';
  el('rstatus').textContent=''; el('rverdict').textContent='…';
  el('rplay').textContent='▶';
  el('rsummary').textContent=
    `scan [${from}‥${to}] at ${cshort(c.id)} · one descent + leaf hops · `+
    `${total? total-res.fetched.size:0} of ${total} chunks never touched — a prefix over ordered keys IS this range`;
  el('simnarr').innerHTML=`<b>scan ${from}‥${to}</b> — descend once, walk right, stop at the first key past the end; the stop predicate needs no foreknowledge of the last key.`;
  if(reduced){ applyStepsUpTo(res.steps.length-1); }
  else { applyStepsUpTo(0); startReadPlay(); }
}
el('scanbtn').onclick=()=>{
  const a=parseInt(el('scanfrom').value,10), b=parseInt(el('scanto').value,10);
  if(!Number.isFinite(a)||!Number.isFinite(b)){ hint('type both range ends'); return; }
  if(a>b){ hint('from must be ≤ to'); return; }
  doScan(a,b);
};
el('scanto').addEventListener('keydown',e=>{ if(e.key==='Enter') el('scanbtn').click(); });

el('readbtn').onclick=()=>{
  const v=parseInt(el('readkey').value,10);
  if(Number.isFinite(v)) doRead(v); else hint('type an integer key to read');
};
el('readkey').addEventListener('keydown',e=>{ if(e.key==='Enter') el('readbtn').click(); });

/** Ancestor closure over the commit DAG (both parents of a merge commit). */
function ancestorsOf(id){
  const seen=new Set(); const st=[id];
  while(st.length){ const i=st.pop();
    if(i==null || i<0 || seen.has(i)) continue;
    seen.add(i); const cm=commits[i];
    st.push(cm.parent); if(cm.parent2!=null) st.push(cm.parent2); }
  return seen;
}
/** Merge base: the common ancestor with the highest id — exact for the simple
    histories this instrument produces (a criss-cross would need a real
    lowest-common-ancestor pass; the engine's own findLCA caveat applies). */
function mergeBase(a,b){
  const A=ancestorsOf(a), B=ancestorsOf(b);
  let best=-1; A.forEach(i=>{ if(B.has(i) && i>best) best=i; });
  return best;
}

el('mergebtn').onclick=()=>{
  const other=el('mergesel').value;
  if(!currentBranch || !branches.has(other)) return;
  const oursId=branches.get(currentBranch), theirsId=branches.get(other);
  if(oursId===theirsId){ el('simnarr').innerHTML=`<b>merge</b>: '${other}' and '${currentBranch}' point at the same commit — nothing to do.`; return; }
  const baseId=mergeBase(oursId, theirsId);
  if(baseId===theirsId){ el('simnarr').innerHTML=`<b>merge</b>: '${other}' (${cshort(theirsId)}) is already an ancestor of '${currentBranch}' — already up to date.`; return; }
  if(baseId===oursId){
    // fast-forward: ours never diverged — the ref just moves, no commit minted
    branches.set(currentBranch, theirsId);
    selected=theirsId; renderAll();
    el('simnarr').innerHTML=`<b>fast-forward</b>: '${currentBranch}' had no commits of its own since the base, so the ref simply moved → ${cshort(theirsId)}. No merge commit, no new chunks.`;
    return;
  }
  const A=new Map(commits[baseId].entries.map(e=>[e.k,e.v]));
  const O=new Map(commits[oursId].entries.map(e=>[e.k,e.v]));
  const T=new Map(commits[theirsId].entries.map(e=>[e.k,e.v]));
  const keys=[...new Set([...A.keys(),...O.keys(),...T.keys()])].sort((x,y)=>x-y);
  const merged=[]; const decisions=[]; let same=0, conflicts=0;
  for(const k of keys){
    const av=A.get(k), ov=O.get(k), tv=T.get(k);
    if(ov===tv){ if(ov!==undefined) merged.push({k,v:ov}); same++; }
    else if(ov===av){
      if(tv!==undefined) merged.push({k,v:tv});
      decisions.push({k, txt:`k=${k}: only theirs changed (${av===undefined?'added':tv===undefined?'deleted':'v'+av+'→v'+tv}) → take theirs`});
    } else if(tv===av){
      if(ov!==undefined) merged.push({k,v:ov});
      decisions.push({k, txt:`k=${k}: only ours changed → keep ours (${ov===undefined?'deleted':'v'+ov})`});
    } else {
      conflicts++;
      const v=Math.max(ov??-1, tv??-1);
      if(v>=0) merged.push({k,v});
      decisions.push({k, conflict:true,
        txt:`k=${k}: CONFLICT — both sides changed (ours ${ov===undefined?'deleted':'v'+ov} vs theirs ${tv===undefined?'deleted':'v'+tv}) → policy: take the larger value (${v>=0?'v'+v:'delete'})`});
    }
  }
  animWrites=false;
  let mc;
  try{ mc=commitOp(`merge ${other}→${currentBranch}`, merged, oursId, null, {parent2:theirsId}); }
  finally{ animWrites=true; }
  startMergeTrace(mc, {baseId, other, decisions, same, conflicts, oursId, theirsId});
};

function startMergeTrace(mc, m){
  cancelReadAnim();
  const steps=[];
  steps.push({type:'msum', txt:`three-way merge: ours ${cshort(m.oursId)} ('${currentBranch}') + theirs ${cshort(m.theirsId)} ('${m.other}') against base ${cshort(m.baseId)} (their common ancestor)`});
  steps.push({type:'msum', txt:`${m.same} keys identical on both sides — never compared individually (the engine skips them wholesale by subtree hash)`});
  m.decisions.forEach(d=>steps.push({type:'mkey', ek:d.k, txt:d.txt}));
  steps.push({type:'wroot', node:mc.rootHash? POOL.get(mc.rootHash):null,
    txt:`merged tree built → root ${mc.rootHash? mc.rootHash.slice(0,10):'∅'} · ${mc.written.size} chunks written`});
  steps.push({type:'wcommit', txt:`merge commit ${cshort(mc.id)} minted with TWO parents (${cshort(m.oursId)}, ${cshort(m.theirsId)}) — the dashed edge in the graph`});
  steps.push({type:'wmanifest', txt:`manifest: '${currentBranch}' re-points → ${cshort(mc.id)} — '${m.other}' still points where it was`});
  readAnim={mode:'merge', steps, pos:-1, res:null, k:null, commit:mc.id, written:mc.written, timer:null,
            conflicts:m.conflicts, group:newTraceGroup(`MERGE ${m.other} → ${currentBranch} = ${cshort(mc.id)}`)};
  el('readbar').hidden=false;
  el('rtitle').textContent='merge';
  el('rstatus').textContent=''; el('rverdict').textContent='…';
  el('rplay').textContent='▶';
  el('rsummary').textContent=
    `merge ${m.other} into ${currentBranch} · ${m.decisions.length} keys decided · ${m.conflicts} conflict${m.conflicts===1?'':'s'} · ${m.same} untouched`;
  if(reduced){ applyStepsUpTo(steps.length-1); }
  else { applyStepsUpTo(0); startReadPlay(); }
}

el('mktag').onclick=()=>{
  const name=(el('branchname').value||'').trim();
  if(!/^[a-z0-9][a-z0-9._-]{0,19}$/i.test(name)){
    el('simnarr').innerHTML='<b>tag name</b>: 1–20 characters — letters, digits, dot, dash, underscore.';
    return;
  }
  if(tags.has(name) || branches.has(name)){
    el('simnarr').innerHTML=`<b>'${name}'</b> is already a ${tags.has(name)?'tag':'branch'}.`;
    return;
  }
  const th=mintTag(name, cur().chash);
  const tobj=POOL.get(th); if(tobj.bornAt==null) tobj.bornAt=selected;
  tags.set(name, th);
  if(persistOK) idbPut(CHUNKS, th, tobj);
  el('branchname').value='';
  renderAll();
  el('simnarr').innerHTML=`<b>tag '${name}'</b> minted → ⊙${th.slice(0,10)} at ${cshort(selected)} — an immutable, content-addressed pin: `+
    `it cannot move, only be deleted, and it retains its commit through every sweep policy.`;
};

el('mkbranch').onclick=()=>{
  const name=(el('branchname').value||'').trim();
  if(!/^[a-z0-9][a-z0-9._-]{0,19}$/i.test(name)){
    el('simnarr').innerHTML='<b>branch name</b>: 1–20 characters — letters, digits, dot, dash, underscore.';
    return;
  }
  if(branches.has(name)){
    el('simnarr').innerHTML=`<b>branch '${name}'</b> already exists (→ ${cshort(branches.get(name))}).`;
    return;
  }
  branches.set(name, selected); currentBranch=name; el('branchname').value='';
  renderAll();
  el('simnarr').innerHTML=`<b>branch '${name}'</b> created at ${cshort(selected)} — a ref is just a name→commit pointer in the manifest; creating it wrote zero chunks.`;
};
el('branchname').addEventListener('keydown',e=>{ if(e.key==='Enter') el('mkbranch').click(); });

let badHashes=new Set();
el('corruptbtn').onclick=()=>{
  const c=cur();
  if(!c.rootHash){ el('simnarr').innerHTML='<b>corrupt</b>: the tree is empty — nothing to damage.'; return; }
  const live=[...reach(c.rootHash)].map(h=>POOL.get(h)).filter(n=>n && !n.children && n.entries && n.entries.length);
  let target= selectedKey!=null? live.find(n=>n.entries.some(e=>e.k===selectedKey)) : null;
  if(!target) target=live[Math.floor(mulberry(fnv('cor'+(rngCounter++)))()*live.length)];
  if(!target) return;
  target.entries[0].v+=1000;               // the bytes changed…
  if(persistOK) idbPut(CHUNKS,target.hash,target); // …and the lie is durable
  badHashes.add(target.hash);
  renderStore(); renderRibbon();
  el('simnarr').innerHTML=`<b>corrupted</b> leaf ${target.hash.slice(0,10)} — its bytes changed but its NAME did not. `+
    `The ribbon already shows the wrong value; any read through it halts on the re-hash, and Verify store finds it cold.`;
};
let resetArmed=null;
el('resetdb').onclick=async()=>{
  const b=el('resetdb');
  if(!resetArmed){
    // arm-then-confirm: destructive, so the first click only warns
    resetArmed=setTimeout(()=>{ resetArmed=null; b.classList.remove('armed'); b.textContent='Reset storage'; },3000);
    b.classList.add('armed'); b.textContent='Click again to erase';
    el('simnarr').innerHTML='<b>reset storage</b>: this DELETES the IndexedDB database and the current session — click again within 3 seconds to confirm.';
    return;
  }
  clearTimeout(resetArmed); resetArmed=null;
  b.classList.remove('armed'); b.textContent='Reset storage';
  persistOK=false;               // stop the write-behind while the disk is gone
  await idbDelete();             // remove the database FILE, not just its rows
  idb=await idbOpen(); persistOK=!!idb;
  const note=el('persistnote');
  if(note) note.textContent= persistOK? '· persisted to IndexedDB — reloads keep your history'
                                      : '· memory-only (browser storage unavailable here)';
  freshBuild();
  el('simnarr').innerHTML='<b>storage reset</b> — the IndexedDB database was deleted and recreated; '+
    'a fresh genesis world is live (and already persisted). The disk and the working set agree again.';
};

el('verifybtn').onclick=()=>{
  badHashes=new Set(); let ok=0, skip=0;
  for(const h of POOL.keys()){
    const r=verifyChunk(h);
    if(r.ok===false) badHashes.add(h);
    else if(r.skip) skip++;
    else ok++;
  }
  renderStore();
  el('simnarr').innerHTML=`<b>store verification</b>: ${ok} ok · `+
    (badHashes.size? `<span style="color:var(--orph)">${badHashes.size} CORRUPT</span>`:'0 corrupt')+
    (skip? ` · ${skip} unverifiable (children swept)`:'')+
    ` — no external truth needed: every name is its own checksum.`;
};

el('gckeep').addEventListener('change',()=>{ if(commits.length) renderStats(); });

/* ---------- file-based backup: packs (classic File API — works on file://) ----------
   Export builds the pack ONCE and opens a modal showing its JSON (highlighted);
   Download and Copy both emit exactly the displayed text — what you see IS the
   pack, one source of truth for eyes, clipboard, and disk. */
let packText=null, packName='pack.json';
el('exportbtn').onclick=()=>{
  if(!commits.length) return;
  const pack={
    format:'wp-pack-v2', // v2: internal preimages carry the subtree-count vector
    cfg:cur().cfg,
    refs:{ branches:[...branches].map(([n,id])=>[n, commits[id]? commits[id].chash : null]),
           tags:[...tags], currentBranch },
    selectedChash:cur().chash,
    log:commits.map(c=>c.chash),
    chunks:[...POOL.entries()].map(([h,n])=>[h, {...n, bornAt:undefined}]), // birth is view-local
  };
  packText=JSON.stringify(pack,null,2);
  packName=`prolly-world-${cur().chash.slice(0,8)}.json`;
  const bytes=new Blob([packText]).size;
  el('packmeta').textContent=`${pack.chunks.length} objects · ${pack.log.length} commits · ${fmtBytes(bytes)}`;
  // very large packs skip highlighting (pathological worlds only) — still escaped
  el('packjson').innerHTML = packText.length>500000 ? escHtml(packText) : highlightJson(packText);
  el('packmodal').hidden=false;
  el('packjson').scrollTop=0;
  el('simnarr').innerHTML=`<b>pack built</b> — ${pack.chunks.length} objects, ${fmtBytes(bytes)}: `+
    `refs + reflog + every chunk, shown as the JSON that travels. A pack is just a container of `+
    `content-addressed objects; the names travel with the bytes.`;
};
el('packdownload').onclick=()=>{
  if(packText==null) return;
  const blob=new Blob([packText],{type:'application/json'});
  const a=document.createElement('a');
  a.href=URL.createObjectURL(blob);
  a.download=packName;
  a.click();
  URL.revokeObjectURL(a.href);
  el('simnarr').innerHTML=`<b>pack exported</b> — ${packName} (${fmtBytes(blob.size)}), byte-identical to the modal's JSON.`;
};
el('packcopy').onclick=async()=>{
  if(packText==null) return;
  const b=el('packcopy');
  try{
    await navigator.clipboard.writeText(packText);
    b.textContent='Copied ✓';
  }catch(e){
    b.textContent='Copy failed';
  }
  setTimeout(()=>{ b.textContent='Copy'; },1200);
};
function closePackModal(){ el('packmodal').hidden=true; }
el('packclose').onclick=closePackModal;
el('packbackdrop').onclick=closePackModal;
document.addEventListener('keydown',e=>{
  if(e.key==='Escape' && !el('packmodal').hidden) closePackModal();
});
el('importbtn').onclick=()=>el('importfile').click();
el('importfile').addEventListener('change', async ev=>{
  const f=ev.target.files && ev.target.files[0];
  ev.target.value='';
  if(!f) return;
  let pack;
  try{ pack=JSON.parse(await f.text()); }
  catch(e){ el('simnarr').innerHTML='<b>import failed</b> — not parseable JSON.'; return; }
  if(pack.format!=='wp-pack-v2' || !Array.isArray(pack.log) || !Array.isArray(pack.chunks)){
    el('simnarr').innerHTML='<b>import failed</b> — not a wp-pack-v2 file'+
      (pack.format==='wp-pack-v1'? ' (a v1 pack: its internal nodes predate the subtree-count vector, so their bytes can no longer hash to their names — the pre-1.0 rule: readers require the new shape, no defensive parsing)':'')+'.';
    return;
  }
  cancelReadAnim(); diffBase=null; selectedKey=null; pickedChunk=null; badHashes=new Set();
  // the receiving store DEDUPS BY NAME and RE-HASHES every incoming object —
  // a pack is untrusted bytes; the boundary verifies, never trusts
  let fresh=0, dup=0, refused=0;
  for(const [h,obj] of pack.chunks){
    if(POOL.has(h)){ dup++; continue; }
    const pre=preimageOf(obj);
    if(!pre || hid(pre)!==h){ refused++; continue; }
    POOL.set(h,obj); fresh++;
  }
  const cfg=pack.cfg||{avg:5,seed:7};
  const {idx, ghosts}=rebuildViewFromLog(pack.log, cfg);
  branches=new Map(((pack.refs&&pack.refs.branches)||[]).map(([n,ch])=>[n, idx.get(ch) ?? 0]));
  tags=new Map((pack.refs&&pack.refs.tags)||[]);
  currentBranch=(pack.refs&&pack.refs.currentBranch)??null;
  selected= idx.get(pack.selectedChash) ?? commits.length-1;
  if(commits[selected] && commits[selected].pruned){
    const i2=commits.findIndex(c=>!c.pruned); if(i2>=0) selected=i2;
  }
  el('avg').value=String(cfg.avg); el('seed').value=cfg.seed;
  naiveBytes=commits.reduce((a,c)=>a+c.total,0);
  traceLog=[];
  persistFullStore();
  renderAll();
  el('simnarr').innerHTML=`<b>pack imported</b>: ${fresh} new object${fresh===1?'':'s'}, ${dup} already present (dedup by name)`+
    (refused? `, <span style="color:var(--orph)">${refused} REFUSED</span> (failed the re-hash — a pack is untrusted bytes)`:'')+
    (ghosts? `, ${ghosts} ghost${ghosts===1?'':'s'}`:'')+
    ` — world switched to ${cshort(selected)}. The previous world's chunks remain in the store until a sweep.`;
});

/* ---------- the writer race: optimistic concurrency via compare-and-set ---------- */
el('racebtn').onclick=()=>{
  if(!currentBranch){
    el('simnarr').innerHTML='<b>race</b>: check out a branch first — the race targets a ref.';
    return;
  }
  const baseId=branches.get(currentBranch);
  const base=commits[baseId];
  if(!base.entries.length){ el('simnarr').innerHTML='<b>race</b>: give the writers a tree to fight over first.'; return; }
  const rng=mulberry(fnv('race'+(rngCounter++)+el('seed').value));
  const pick=(es,not)=>{
    for(let t=0;t<60;t++){
      if(es.length<2){ const k=50+Math.floor(rng()*90); if(k!==not && !es.some(e=>e.k===k)) return k; continue; }
      const i=Math.floor(rng()*(es.length-1));
      const a=es[i].k, b=es[i+1].k;
      if(b-a>1){ const k=a+1+Math.floor(rng()*(b-a-1)); if(k!==not && !es.some(e=>e.k===k)) return k; }
    }
    return es[es.length-1].k+11;
  };
  const esBase=base.entries.map(e=>({...e}));
  const kA=pick(esBase,null), kB=pick(esBase,kA);
  const baseHash=base.chash;
  // WRITER B builds first — concurrently, against the same snapshot. Nothing is
  // mutated by building: it only mints chunks.
  const before=new Set(POOL.keys());
  const esB1=[...esBase,{k:kB,v:1}].sort((x,y)=>x.k-y.k);
  const rB1=buildTree(esB1, base.cfg.avg, base.cfg.seed);
  const abandoned=[...POOL.keys()].filter(h=>!before.has(h));
  abandoned.forEach(h=>{ POOL.get(h).bornAt=-1; }); // the abandoned-work band
  // WRITER A lands: its compare-and-set on the ref succeeds
  animWrites=false;
  let cA, cB;
  try{
    const esA=[...esBase,{k:kA,v:1}].sort((x,y)=>x.k-y.k);
    cA=commitOp(`insert ${kA} (writer A)`, esA, baseId, kA);
    // WRITER B's CAS fails (the ref moved) → re-read, REPLAY the edit, retry
    const esB2=[...cA.entries.map(e=>({...e})),{k:kB,v:1}].sort((x,y)=>x.k-y.k);
    cB=commitOp(`insert ${kB} (writer B, retried)`, esB2, cA.id, kB);
  } finally { animWrites=true; }
  const stillAbandoned=abandoned.filter(h=>POOL.get(h) && POOL.get(h).bornAt===-1);
  const steps=[
    {type:'msum', txt:`writer A and writer B both read '${currentBranch}' → ${cshort(baseId)} — the same immutable snapshot; neither needs a lock to BUILD`},
    {type:'msum', txt:`A builds: insert ${kA} → root ${cA.rootHash.slice(0,10)} · B builds CONCURRENTLY: insert ${kB} → root ${rB1.slice(0,10)} — building only mints chunks, mutates nothing`},
    {type:'msum', txt:`A: compare-and-set('${currentBranch}': expected ⋄${baseHash.slice(0,8)} → ${cshort(cA.id)}) → SUCCESS — the ref advances`},
    {type:'stop', txt:`B: compare-and-set('${currentBranch}': expected ⋄${baseHash.slice(0,8)} → its root) → FAILED — the ref is now ${cshort(cA.id)}, not what B read`},
    {type:'msum', txt:`B retries: re-read '${currentBranch}' (${cshort(cA.id)}), REPLAY the edit on the new base — its first tree (${stillAbandoned.length} chunks) is abandoned work, already sweepable`},
    {type:'msum', txt:`B: compare-and-set('${currentBranch}': expected ${cshort(cA.id)} → ${cshort(cB.id)}) → SUCCESS`},
    {type:'msum', txt:`linear history, no fork: ${cshort(baseId)} → ${cshort(cA.id)} → ${cshort(cB.id)}. Optimistic concurrency: build freely, swap atomically, retry on conflict — this is why real histories are lines, not accidental forests`},
  ];
  readAnim={mode:'cas', steps, pos:-1, res:null, k:null, commit:cB.id, written:null, timer:null,
            group:newTraceGroup(`RACE A(${kA}) vs B(${kB}) on '${currentBranch}'`)};
  el('readbar').hidden=false;
  el('rtitle').textContent='writer race';
  el('rstatus').textContent=''; el('rverdict').textContent='…';
  el('rplay').textContent='▶';
  el('rsummary').textContent=
    `two writers, one ref · loser retries by replaying on the new base · B's discarded attempt sits in the store as sweepable garbage`;
  el('simnarr').innerHTML=`<b>writer race</b> on '${currentBranch}' — watch the compare-and-set arbitrate; the history stays linear.`;
  if(reduced){ applyStepsUpTo(steps.length-1); }
  else { applyStepsUpTo(0); startReadPlay(); }
};

el('rungc').onclick=()=>{
  const {keepIdx, keep}=computeKeepSet();
  let swept=0;
  for(const h of [...POOL.keys()]) if(!keep.has(h)){ POOL.delete(h); swept++; }
  commits.forEach(c=>{ c.pruned = c.rootHash!=null && !keep.has(c.rootHash); });
  if(pickedChunk && !POOL.has(pickedChunk)) pickedChunk=null;
  persistFullStore();
  renderAll();
  el('simnarr').innerHTML=`<b>reachability sweep</b> deleted ${swept} unreachable chunks; ${keep.size} kept `+
    `(retained roots: ${[...keepIdx].sort((a,b)=>a-b).map(cshort).join(', ')}). `+
    `Pruned commits are gone for good — the ReachabilityWalker's walk IS the definition of alive.`;
};
document.querySelectorAll('.pane .zctl button').forEach(b=>{
  b.onclick=()=>{
    const ctl=paneCtl[b.closest('.pane').querySelector('svg').id];
    if(!ctl) return;
    if(b.dataset.z==='fit') ctl.svg.call(ctl.zoom.transform, ctl.fit);
    else ctl.svg.call(ctl.zoom.scaleBy, b.dataset.z==='in'? 1.35 : 1/1.35);
  };
});
// route by mode: in REAL mode the sim's renderers must NOT stomp the engine's panes
// (a real bug: resizing the browser window in REAL mode redrew the SIM trees — the
// G-1/G-2 mode-isolation family, caught by the README capture script's fullPage
// screenshots, which resize the viewport)
addEventListener('resize',()=>{
  if(dataMode==='real'){ renderRealAll(); return; }
  if(commits.length) { renderTrees(); renderStats(); }
});


/* ---------- data modes (prolly-playground-service) ----------
   One axis, three positions — WHICH STORE IS THIS PAGE?
     sim         the in-page toy engine; everything local (the default).
     sim+shadow  the sim drives; its KEY SET mirrors to the real Java engine
                 after every world change through one funnel: a renderAll wrap
                 with a key-set-diff guard. Values stay sim-side (the panel
                 says so); unreachable backend degrades to a hint + sim mode.
     real        the backend IS the store: the page renders the engine's own
                 tree and every write goes over HTTP; the sim world is left
                 untouched. Unreachable backend is an explicit retry state —
                 REAL mode never silently falls back (a clear switch stays
                 where you put it).
   Switching modes NEVER migrates data: each mode re-renders from its own
   store (REAL re-fetches; SIM re-renders the in-memory world). Entering REAL
   disarms the shadow mirror so a later return to sim cannot clobber real
   edits with a mirror push. */
// ?real=<base-url> overrides the backend (e2e runs the service on an isolated port).
// Served BY the service (http/https) → same-origin; opened as file:// → localhost:8080.
const REAL_API=(new URLSearchParams(location.search).get('real')
  ?? (location.protocol.startsWith('http')? '' : 'http://localhost:8080'))+'/api';
let dataMode='sim';   // 'sim' | 'shadow' | 'real' — which store this page renders + writes
let realOn=false, realMirrored=null, realBusy=false;

async function realFetch(path, opts){
  const r=await fetch(REAL_API+path, {headers:{'Content-Type':'application/json'}, ...opts});
  if(!r.ok){ const e=new Error('HTTP '+r.status); e.status=r.status; throw e; }
  return r.json();
}
function realPanel(html){ const p=el('realpanel'); p.hidden=false; p.innerHTML=html; }
function modeButtons(m){
  for(const id of ['mode-sim','mode-shadow','mode-real']){
    const b=el(id), on=id==='mode-'+m;
    b.classList.toggle('on',on); b.setAttribute('aria-pressed',String(on));
  }
}
/** Shadow degrade: the mirror can't run without a backend, so the MODE reverts
    to sim (the hint stays visible). REAL mode never comes through here — its
    unreachable state is explicit-with-retry (D-6). */
function realDown(){
  realOn=false; dataMode='sim';
  try{ localStorage.setItem('wp-data-mode','sim'); }catch(e){}
  modeButtons('sim'); document.body.classList.remove('wp-real');
  el('realsection').hidden=false; // the hint must stay visible after the mode reverts
  realPanel(`<span class="rr-err">backend unreachable</span> — start it, then re-select a mode:<br>`+
    `<code>mvn -pl prolly-playground-service spring-boot:run</code>`);
}

async function realSync(){
  if(!realOn || realBusy) return;
  const keys=cur().entries.map(e=>e.k);
  const want=new Set(keys);
  if(realMirrored && want.size===realMirrored.size && keys.every(k=>realMirrored.has(k))) return;
  realBusy=true;
  try{
    let resp, sent;
    if(realMirrored===null){
      resp=await realFetch('/tree',{method:'PUT',body:JSON.stringify({keys})});
      sent=`replaced world (${keys.length} keys)`;
    }else{
      const puts=keys.filter(k=>!realMirrored.has(k));
      const dels=[...realMirrored].filter(k=>!want.has(k));
      let written=[];
      if(puts.length){ resp=await realFetch('/tree/keys',{method:'POST',body:JSON.stringify({keys:puts})}); written=written.concat(resp.written); }
      if(dels.length){ resp=await realFetch('/tree/keys',{method:'DELETE',body:JSON.stringify({keys:dels})}); written=written.concat(resp.written); }
      if(!resp){ realBusy=false; return; }
      resp={...resp, written};
      sent=`${puts.length} put${puts.length===1?'':'s'} · ${dels.length} delete${dels.length===1?'':'s'}`;
    }
    realMirrored=want;
    const simWrote=cur().written? cur().written.size : 0;
    const gap= simWrote>resp.written.length
      ? ` — the sim splits at toy scale (~35 B chunks); the engine's 512 B–16 KiB chunks hold hundreds of keys, so the same edit touches fewer, bigger nodes`
      : '';
    realPanel(
      `root <span class="rr-hash" data-rh="${resp.rootHash??''}">${resp.rootHash? '⋄'+resp.rootHash.slice(0,10):'∅'}</span>`+
      ` · ${resp.treeCount} keys · height ${resp.height<0?'∅':resp.height} · ${resp.storedNodes} nodes stored<br>`+
      `mirrored: ${sent} → <b class="rr-cmp">real engine wrote ${resp.written.length} node${resp.written.length===1?'':'s'}</b>`+
      ` (sim wrote ${simWrote} chunk${simWrote===1?'':'s'})${gap}<br>`+
      `<span style="color:var(--faint)">key set only — values stay sim-side · click the root hash for the REAL node, CAS-verified</span>`);
    const h=el('realpanel').querySelector('.rr-hash');
    if(h && h.dataset.rh) h.onclick=()=>inspectReal(h.dataset.rh);
  }catch(e){ realDown(); }
  finally{ realBusy=false; }
}

/** Fetch one REAL node and render it into the inspector — parsed from stored
    bytes by the actual Node reader, its name re-hashed live (verified). */
/* The REAL inspector is DECLARATIVE (write-path-explorer.bind.js): its markup is the
   #realinspect template in the .html; this only assigns data. The whole surface is a
   data-each over a ONE-item array — [] means the REAL content does not exist in the
   DOM at all (the sim's strict single-element locators for .ihash/.ih never see
   duplicates), and each inspect stamps FRESH (details closed, hexdump at '…' — exactly
   the old innerHTML-rebuild semantics). Events are delegated on the permanent
   container; the hexdump stays imperative and unbound. */
const realInsVm = Bind.mount(el('realinspect'), { nodes: [] });
el('realinspect').addEventListener('click', (ev)=>{
  const h=ev.target.closest('.rr-hash');
  if(h && h.dataset.rh) inspectReal(h.dataset.rh);
});
// details 'toggle' does not bubble, but a CAPTURE listener on the ancestor still fires —
// and it survives the per-inspect re-stamp that a listener on the details itself wouldn't
el('realinspect').addEventListener('toggle', (ev)=>{
  const det=ev.target.closest('details.ibytes');
  if(det && det.open && realInsVm.nodes.length) renderIbytes(det, realInsVm.nodes[0].hash);
}, true);
async function inspectReal(hash){
  try{
    const n=await realFetch('/nodes/'+hash);
    const keys=n.keys.length? `${n.keys[0]}‥${n.keys[n.keys.length-1]} (${n.keys.length} keys)` : '∅';
    realInsVm.nodes = [{
      kind: n.leaf? 'leaf' : 'internal L'+n.level,
      sizeSep: ` · ${n.byteSize} B · `,
      verified: !!n.verified, mismatch: !n.verified,
      hash: n.hash,
      keysLine: `keys ${keys} · subtree ${n.treeCount}`,
      kids: n.children.map(c=>({hash:c.hash, short:c.hash.slice(0,10), cum:`≤${c.subtreeCountPrefix} cum`})),
      hasKids: n.children.length>0,
      byteSize: String(n.byteSize),
    }];
    el('siminspect').hidden=true; el('realinspect').hidden=false; // works from shadow mode too
  }catch(e){ if(dataMode==='real') realUnreachable(e); else realDown(); }
}

/* The REAL narration is DECLARATIVE: #realnarr's template binds these fields, and
   EVERY string is data (no static text in the template) — on mode exit the clear below
   empties it, which is load-bearing: specs assert #narration does NOT contain
   'REAL ENGINE' back in sim, and hidden text still counts in textContent. The sim's
   ~45 imperative writers own the sibling #simnarr; no stash/restore needed anymore —
   the sim's narration simply sits untouched (and hidden) while REAL mode runs. */
const realNarrVm = Bind.mount(el('realnarr'), { msgs: [] });
/** The one writer: hash null = no chip; msgs=[] (mode exit) = no REAL text in the DOM. */
function realNarr(pre, hash, tail){
  realNarrVm.msgs = [{ lead:'REAL ENGINE', pre, showHash:!!hash, hash:hash||'',
    hashShort:hash? '⋄'+hash.slice(0,10) : '', tail:tail||'' }];
}
el('realnarr').addEventListener('click', (ev)=>{
  const h=ev.target.closest('.rr-hash');
  if(h && h.dataset.rh) inspectReal(h.dataset.rh);
});

/** The mode switch (D-1/D-2). Never migrates data: each arm re-renders from
    its own store, and entering REAL disarms the mirror. */
function setDataMode(m){
  const prev=dataMode;
  dataMode=m;
  try{ localStorage.setItem('wp-data-mode',m); }catch(e){}
  modeButtons(m);
  document.body.classList.toggle('wp-real', m==='real');
  // inert, not just dimmed: pointer-events:none does NOT stop keyboard focus —
  // a focused #lensperturb or #gckeep could drive the sim from REAL mode (G-1)
  document.querySelectorAll('.simonly').forEach(x=>{
    if(m==='real') x.setAttribute('inert',''); else x.removeAttribute('inert');
  });
  el('realtools').hidden = (m!=='real');
  el('realbulkrow').hidden = (m!=='real');
  el('realbulkpresets').hidden = (m!=='real');
  el('realbenchrow').hidden = (m!=='real');
  el('simnarr').hidden = (m==='real');
  el('realnarr').hidden = (m!=='real');
  if(m!=='real'){ el('rootlog').hidden=true; el('realstriprow').hidden=true; el('realcrumb').hidden=true; realZoom=[]; el('benchout').hidden=true;
    // clear, don't just hide: hidden text still counts in textContent, and the sim's
    // strict single-element locators must never see the REAL twins
    realNarrVm.msgs=[]; realInsVm.nodes=[];
    el('siminspect').hidden=false; el('realinspect').hidden=true; }
  if(m==='real'){ el('realnudge').hidden=true;
    try{ localStorage.setItem('wp-real-nudge','1'); }catch(e){} }
  selectedKey=null; pickedChunk=null;
  el('realsection').hidden = (m==='sim');
  if(m==='shadow'){
    realOn=true; realMirrored=null;
    el('realpanel').hidden=false; realPanel('connecting…');
    realFetch('/tree').then(()=>renderAll()).catch(()=>realDown());
    return;
  }
  if(m==='real'){
    realOn=false; realMirrored=null;   // no mirror can fire from REAL mode
    el('realpanel').hidden=false; realPanel('connecting to the real engine…');
    realBlank(' — connecting…'); // G-2: the sim must not stay clickable in the connect window
    realEnter();
    return;
  }
  // sim: the world sat untouched while we were away — just re-render it
  realOn=false; el('realpanel').hidden=true;
  for(const id of SIM_ONLY_CONTROLS){ const b=el(id); if(b) b.disabled=false; }
  renderAll(); // syncButtons re-owns the selection-dependent ones
}

/* REAL mode world: fetched from the backend, shaped like sim nodes so the
   pane machinery renders it through the pool seam (D-4). prevRoot/prevPool is
   the tree as of the previous load — still renderable after a write, because
   the engine's content-addressed store never deletes (copy-on-write). */
let realWorld=null; // {stats, pool:Map, refs, written:Set, prevRoot, prevPool}
function buildRealPool(views){
  const pool=new Map();
  for(const v of views){
    pool.set(v.hash,{hash:v.hash, level:v.level, count:Number(v.treeCount),
      children: v.leaf? null : v.children.map(c=>c.hash),
      span: v.keys.length? [v.keys[0], v.keys[v.keys.length-1]] : [0,0],
      entries: v.leaf? v.keys.map(k=>({k,v:1})) : null,
      byteSize:v.byteSize, verified:v.verified});
  }
  return pool;
}
async function realLoad(written){
  const [stats, views, refs]=await Promise.all([
    realFetch('/tree'), realFetch('/tree/nodes'), realFetch('/nodes')]);
  realWorld={stats, pool:buildRealPool(views), refs, written: written||new Set(),
    prevRoot: realWorld? realWorld.stats.rootHash : undefined,
    prevPool: realWorld? realWorld.pool : null};
  viewingRoot=null; // any fresh load returns the page to head
  realZoom=[];
  logRoot(stats);
}

/* ---------- the session root-log: time travel from the CAS alone (D-4) ----------
   Every root this session produced is still IN the store (nothing is deleted),
   so any of them renders read-only via ?root=. The log is client-side and dies
   with the page — durable history is the commit layer's job, and saying so is
   the lesson. */
let rootLog=[];        // [{root, treeCount}] session-only, oldest first
let viewingRoot=null;  // {root, pool} — read-only view of an old root
function logRoot(stats){
  if(!stats.rootHash) return;
  if(rootLog.length && rootLog[rootLog.length-1].root===stats.rootHash) return;
  rootLog.push({root:stats.rootHash, treeCount:Number(stats.treeCount)});
}
/* Bind-bound (write-path-explorer.bind.js): the chips are data; the template lives in
   the .html. Clicks are delegated on the container — chips re-stamp on every render. */
const rootLogVm = Bind.mount(el('rootlog'), { chips: [] });
el('rootlog').addEventListener('click', (ev)=>{
  const c=ev.target.closest('.rchip');
  if(c && c.dataset.root) viewRealRoot(c.dataset.root);
});
function renderRootLog(){
  const box=el('rootlog');
  const row=el('realstriprow');
  box.hidden = dataMode!=='real' || !rootLog.length;
  row.hidden = box.hidden;
  if(box.hidden) return;
  rootLogVm.chips = rootLog.map(r=>{
    const isHead= realWorld && r.root===realWorld.stats.rootHash;
    const isViewing= viewingRoot && r.root===viewingRoot.root;
    return { root:r.root,
      cls:'rchip'+(isViewing?' viewing': isHead&&!viewingRoot?' cur':''),
      title: isHead? 'the current head'
        : 'a superseded root — its chunks are all still in the store; click to view that tree read-only',
      head:`⋄${r.root.slice(0,8)}`,
      sub:`×${r.treeCount} keys${isHead?' · HEAD':''}` };
  });
  // Bind renders on a microtask; queue the scroll AFTER it (FIFO) so scrollWidth is real
  queueMicrotask(()=>{ box.scrollLeft=box.scrollWidth; });
}
async function viewRealRoot(h){
  realZoom=[];
  if(realWorld && h===realWorld.stats.rootHash){ viewingRoot=null; renderRealAll(); return; }
  try{
    const views=await realFetch('/tree/nodes?root='+h);
    viewingRoot={root:h, pool:buildRealPool(views)};
    renderRealView();
  }catch(e){ realUnreachable(e); }
}
/** Read-only render of a superseded root, straight from the retained store. */
function renderRealView(){
  readPath=null;
  const pool=viewingRoot.pool, rootNode=pool.get(viewingRoot.root);
  d3.select('#beforesvg').selectAll('*').remove(); el('beforeh').textContent='—';
  const displayRoot=realZoom.length? realZoom[realZoom.length-1] : viewingRoot.root;
  realTrunc = truncatedPool(pool, displayRoot);
  renderPane('aftersvg', {id:'REAL', rootHash:displayRoot, written:new Set()},
    h=> realWorld && realWorld.pool.has(h)? 'shared' : 'orph',
    `OLD ROOT ${viewingRoot.root.slice(0,8)} — read-only`, realTrunc.pool, realPick);
  renderRealCrumb(realTrunc);
  const box=el('ribbon'); box.innerHTML='';
  const note=document.createElement('span'); note.className='hint';
  note.textContent='viewing an old root (read-only) — write, refresh, or click the head chip to return · ';
  box.appendChild(note);
  const leaves=[];
  (function walk(hh){ const n=pool.get(hh); if(!n) return;
    if(!n.children){ leaves.push(n); return; } n.children.forEach(walk); })(viewingRoot.root);
  let shown=0;
  for(const n of leaves){
    if(shown>=REAL_RIBBON_CAP) break;
    const cb=document.createElement('div'); cb.className='chunkbox'; cb.dataset.hash=n.hash;
    const tag=document.createElement('span'); tag.className='chash';
    tag.textContent=`${n.hash.slice(0,6)} ×${n.entries.length}`;
    cb.appendChild(tag);
    for(const e of n.entries){
      if(shown>=REAL_RIBBON_CAP) break;
      const kc=document.createElement('button'); kc.className='keycell'; kc.disabled=true;
      kc.textContent=e.k; cb.appendChild(kc); shown++;
    }
    box.appendChild(cb);
  }
  el('ribbonmap').hidden=true;
  realNarr(' — OLD root ', viewingRoot.root,
    ` (read-only) · ${rootNode? rootNode.count:'?'} keys — retention IS history: the store never forgot this tree`);
  renderRootLog();
}

/** Enter REAL mode: fetch the backend's own store and render it.
    Unreachable is an explicit retry state — never a silent fallback (D-6). */
async function realEnter(){
  readPath=null;
  try{ await realLoad(new Set()); }catch(e){ realUnreachable(e); return; }
  realPanel('REAL mode — this page renders and writes the engine\'s own store; the sim sits untouched. '+
    'Dimmed panels are sim-only instruments (commits, branches, byte lens, GC) — the backend deliberately has none.');
  renderAll();
}
/** REAL mode shows NOTHING of the other store: blank every interactive panel.
    Runs synchronously on mode entry (D-2) — the connect window must not leave
    the sim's live panels clickable under a REAL header (G-2, a real defect:
    a gap click in that window wrote the SIM). */
function realBlank(narrationPre, ribbonHint){
  realNarr(narrationPre);
  el('ribbon').innerHTML= ribbonHint? `<span class="hint">${ribbonHint}</span>` : '';
  el('ribbonmap').hidden=true;
  d3.select('#beforesvg').selectAll('*').remove();
  d3.select('#aftersvg').selectAll('*').remove();
  el('beforeh').textContent='—'; el('afterh').textContent='—';
  el('storebands').innerHTML='';
}
/** The D-6 down-state. An HTTP status is a SERVER answer, not unreachability —
    say which one happened (G-3: a 500 used to read "backend unreachable"). */
function realUnreachable(err){
  const httpStatus = err && err.status;
  realPanel((httpStatus
      ? `<span class="rr-err">the service answered HTTP ${httpStatus}</span> — the backend is up but refused this:`
      : `<span class="rr-err">backend unreachable</span> — REAL mode needs the service:`)+
    `<br><code>mvn -pl prolly-playground-service spring-boot:run</code> `+
    `<button id="realretry">retry</button>`);
  const b=el('realpanel').querySelector('#realretry'); if(b) b.onclick=()=>realEnter();
  realBlank(` — ${httpStatus? 'HTTP '+httpStatus : 'backend unreachable'}`,
    'no backend — nothing to show (this mode renders only the engine\'s own store)');
}

/* ---------- REAL mode renderers (D-4: same machinery, the engine's pool) ---------- */

/* Pane cap + zoom-to-subtree: big REAL trees (a +1M insert = ~4,500 nodes) would
   drown the SVG panes, so panes render whole LEVELS until a node budget and
   collapse the frontier internals into dashed ⋯ stubs (level-granular — stubbing
   individual excluded children would render thousands of stubs and save nothing).
   Clicking a stub descends into that subtree; breadcrumbs ascend. Stats, ribbon,
   reads and the store panel keep using the FULL pool — only the drawing is capped. */
const PANE_NODE_CAP=320;
let realZoom=[];      // descent path of subtree roots (empty = the display root)
let realTrunc=null;   // the AFTER pane's current truncated pool (stub lookup for clicks)
function truncatedPool(fullPool, rootHash){
  const levels=[[rootHash]]; const seen=new Set([rootHash]);
  for(;;){
    const next=[];
    for(const h of levels[levels.length-1]){
      const n=fullPool.get(h);
      if(n && n.children) for(const ch of n.children){ if(!seen.has(ch)){ seen.add(ch); next.push(ch); } }
    }
    if(!next.length) break;
    levels.push(next);
  }
  let cut=levels.length, total=0;
  for(let i=0;i<levels.length;i++){
    if(total+levels[i].length>PANE_NODE_CAP && i>=2){ cut=i; break; } // always show root+children
    total+=levels[i].length;
  }
  const pool=new Map(); let rendered=0;
  for(let i=0;i<cut;i++) for(const h of levels[i]){
    const n=fullPool.get(h); if(!n) continue;
    if(i===cut-1 && cut<levels.length && n.children){
      pool.set(h, {...n, children:null, stub:true, stubLabel:`⋯ ×${n.count}`});
    } else pool.set(h, n);
    rendered++;
  }
  return {pool, rendered, totalNodes:seen.size, truncated:cut<levels.length};
}
function realDisplayRoot(){
  const base=viewingRoot? viewingRoot.root : (realWorld? realWorld.stats.rootHash : null);
  return realZoom.length? realZoom[realZoom.length-1] : base;
}
function renderRealCrumb(afterTrunc){
  const box=el('realcrumb');
  const zoomed=realZoom.length>0;
  box.hidden = dataMode!=='real' || (!zoomed && !(afterTrunc && afterTrunc.truncated));
  if(box.hidden) return;
  box.innerHTML='';
  const base=viewingRoot? viewingRoot.root : realWorld.stats.rootHash;
  const mk=(label,i,cur)=>{
    const b=document.createElement('button');
    b.className='crumb'+(cur?' cur':''); b.textContent=label;
    if(!cur) b.onclick=()=>{ realZoom=realZoom.slice(0,i+1); renderRealPaneView(); };
    return b;
  };
  box.appendChild(mk('⌂ ⋄'+base.slice(0,8), -1, !zoomed));
  realZoom.forEach((h,i)=>box.appendChild(mk('⋄'+h.slice(0,8), i, i===realZoom.length-1)));
  const note=document.createElement('span'); note.className='cnote';
  note.textContent= afterTrunc && afterTrunc.truncated
    ? `showing ${afterTrunc.rendered} of ${afterTrunc.totalNodes} nodes under this root — dashed ⋯ stubs collapse subtrees; click one to descend`
    : 'zoomed into a subtree — crumbs ascend';
  box.appendChild(note);
}
function renderRealPaneView(){ if(viewingRoot) renderRealView(); else renderRealPanes(); }
function realPick(h){
  if(realTrunc && realTrunc.pool.get(h) && realTrunc.pool.get(h).stub){
    realZoom.push(h);           // zoom-to-subtree: a stub click descends
    renderRealPaneView();
    return;
  }
  pickedChunk=h; inspectReal(h);
  renderRealPaneView(); renderRealStore();
}
function renderRealPanes(){
  const s=realWorld.stats;
  const havePrev = realWorld.prevRoot!==undefined;
  // the union pool renders superseded nodes too — they are still IN the store
  const unionPool = realWorld.prevPool? new Map([...realWorld.prevPool, ...realWorld.pool]) : realWorld.pool;
  const beforeTrunc = (havePrev && realWorld.prevRoot)? truncatedPool(unionPool, realWorld.prevRoot) : null;
  renderPane('beforesvg', havePrev? {id:'REAL', rootHash:realWorld.prevRoot} : null,
    h=> realWorld.pool.has(h)? 'shared' : 'orph',
    `real root before the last write ${realWorld.prevRoot? realWorld.prevRoot.slice(0,8):'∅'}`,
    beforeTrunc? beforeTrunc.pool : unionPool, realPick);
  const displayRoot=realDisplayRoot();
  realTrunc = displayRoot? truncatedPool(realWorld.pool, displayRoot) : null;
  renderPane('aftersvg', {id:'REAL', rootHash:displayRoot, written:realWorld.written},
    h=> realWorld.written.has(h)? 'new' : 'shared',
    (realZoom.length? `zoom ⋄${displayRoot.slice(0,8)} (of root ${s.rootHash? s.rootHash.slice(0,8):'∅'})`
                    : `REAL root ${s.rootHash? s.rootHash.slice(0,8):'∅'}`),
    realTrunc? realTrunc.pool : realWorld.pool, realPick);
  renderRealCrumb(realTrunc);
}
const REAL_RIBBON_CAP=600; // D-7: real trees hold thousands of keys per node
function realLeaves(){
  const out=[], s=realWorld.stats;
  if(!s.rootHash) return out;
  (function walk(h){ const n=realWorld.pool.get(h); if(!n) return;
    if(!n.children){ out.push(n); return; } n.children.forEach(walk); })(s.rootHash);
  return out;
}
function renderRealRibbon(){
  const box=el('ribbon'); box.innerHTML='';
  const s=realWorld.stats;
  if(!s.rootHash){
    const msg=document.createElement('span'); msg.className='hint';
    msg.textContent='empty real tree — ';
    const b=document.createElement('button'); b.className='primary';
    b.textContent='Insert first key (50)'; b.onclick=()=>doRealInsert(50);
    el('ribbonmap').hidden=true;
    box.appendChild(msg); box.appendChild(b); syncRealButtons(); return;
  }
  const leaves=realLeaves();
  const mkGap=(a,b)=>{
    const g=document.createElement('button'); g.className='gap'; g.textContent='+';
    const ok=b-a>1; g.disabled=!ok;
    g.title=ok? `insert ${a+Math.floor((b-a)/2)} — POST /api/tree/keys` : 'gap full';
    if(ok) g.onclick=()=>doRealInsert(a+Math.floor((b-a)/2));
    return g;
  };
  let shown=0, capped=false, prevKey=null;
  for(const n of leaves){
    if(shown>=REAL_RIBBON_CAP){ capped=true; break; }
    const cb=document.createElement('div');
    cb.className='chunkbox'+(realWorld.written.has(n.hash)?' new':'');
    cb.dataset.hash=n.hash;
    cb.title=`REAL leaf ${n.hash} · ${n.entries.length} keys · ${n.byteSize} B — click the box frame: inspect the stored node`;
    const tag=document.createElement('span'); tag.className='chash';
    tag.textContent=`${n.hash.slice(0,6)} ×${n.entries.length}`;
    cb.appendChild(tag);
    cb.style.cursor='pointer';
    cb.onclick=ev=>{ if(ev.target!==cb) return; realPick(n.hash); };
    cb.onmouseenter=()=>{ const nd=document.querySelector(`#aftersvg g.nd[data-hash="${n.hash}"]`); if(nd) nd.classList.add('hoverlink'); };
    cb.onmouseleave=()=>{ document.querySelectorAll('#aftersvg g.nd.hoverlink').forEach(x=>x.classList.remove('hoverlink')); };
    let firstOfChunk=true;
    for(const e of n.entries){
      if(shown>=REAL_RIBBON_CAP){ capped=true; break; }
      if(prevKey!=null){
        const g=mkGap(prevKey,e.k);
        if(firstOfChunk){ g.classList.add('gapb'); box.appendChild(g); } else cb.appendChild(g);
      }
      firstOfChunk=false;
      const kc=document.createElement('button');
      kc.className='keycell'+(selectedKey===e.k?' sel':'');
      kc.dataset.k=e.k;
      kc.textContent=e.k;
      kc.title=`key ${e.k} — click to select (enables Delete → DELETE /api/tree/keys)`;
      kc.onclick=()=>{ selectedKey= selectedKey===e.k? null : e.k; renderRealRibbon(); };
      cb.appendChild(kc);
      prevKey=e.k; shown++;
    }
    box.appendChild(cb);
  }
  if(capped){
    const note=document.createElement('span'); note.className='hint';
    note.textContent=` … showing the first ${REAL_RIBBON_CAP} of ${s.treeCount} keys — the engine's 512 B–16 KiB chunks hold hundreds each`;
    box.appendChild(note);
  }
  renderRibbonMap(leaves, n=>realWorld.written.has(n.hash));
  syncRealButtons();
}
function renderRealStore(){
  const bands=el('storebands'); bands.innerHTML='';
  el('persistnote').textContent=` · REAL store: server-side, in-memory`;
  const band=document.createElement('div'); band.className='storeband';
  const bl=document.createElement('span'); bl.className='bl';
  bl.textContent='REAL ENGINE node store — first-write order · nothing is ever deleted here (copy-on-write; no sweep)';
  band.appendChild(bl);
  const bt=document.createElement('div'); bt.className='bt';
  const CAP=800; // D-6: long sessions accumulate thousands; never truncate silently
  const refs=realWorld.refs.length>CAP? realWorld.refs.slice(-CAP) : realWorld.refs;
  if(refs.length<realWorld.refs.length){
    const note=document.createElement('span'); note.className='hint';
    note.textContent=`… ${realWorld.refs.length-refs.length} older nodes not shown (${realWorld.refs.length} total) `;
    bt.appendChild(note);
  }
  for(const r of refs){
    const t=document.createElement('button');
    const st= realWorld.written.has(r.hash)? 'snew' : realWorld.pool.has(r.hash)? 'slive' : 'shist';
    t.className='chunk '+st+(pickedChunk===r.hash?' picked':'');
    t.dataset.hash=r.hash;
    t.innerHTML=r.hash.slice(0,6)+(r.level>0?`<sup>L${r.level}</sup>`:'');
    t.title=`${r.level===0?'leaf':'internal L'+r.level} ${r.hash} · ${r.count} entries · `+
      (st==='snew'?'written by the last op':
       st==='slive'?'live: reachable from the current root':
       'historic: unreachable from the current root — kept anyway (the space↔history trade, un-traded)');
    t.onclick=()=>realPick(r.hash);
    bt.appendChild(t);
  }
  band.appendChild(bt);
  bands.appendChild(band);
}
function renderRealStats(){
  const s=realWorld.stats, w=realWorld.written;
  el('s-written').innerHTML=`${w.size}<span class="u"> / ${realWorld.pool.size}</span>`;
  let wb=0; w.forEach(h=>{ const n=realWorld.pool.get(h); if(n) wb+=n.byteSize; });
  let tb=0; realWorld.pool.forEach(n=>tb+=n.byteSize);
  el('s-bytes').innerHTML=`${fmtBytes(wb)}<span class="u"> of ${fmtBytes(tb)} tree${tb?` (${Math.round(100*wb/tb)}%)`:''}</span>`;
  const shared=realWorld.pool.size-w.size;
  el('s-shared').innerHTML=`${shared}<span class="u"> (${realWorld.pool.size?Math.round(100*shared/realWorld.pool.size):0}%)</span>`;
  el('s-height').innerHTML=`${s.height<0?0:s.height+1}<span class="u"> levels · ${s.treeCount} keys</span>`;
  el('s-dedup').innerHTML=`—<span class="u"> sim-only gauge</span>`;
  el('s-gc').innerHTML=`${realWorld.refs.length-realWorld.pool.size}<span class="u"> unreachable of ${realWorld.refs.length} stored — never swept</span>`;
  d3.select('#chart').selectAll('*').remove();
}
function renderRealNarration(){
  const s=realWorld.stats;
  if(s.rootHash){
    realNarr(' — root ', s.rootHash,
      ` · ${s.treeCount} keys · ${realWorld.pool.size} live nodes of ${realWorld.refs.length} stored — every hash on this page is the actual Java engine's`);
  }else{
    realNarr(' — empty tree: no chunks, no root; the manifest points at nothing');
  }
}
function renderRealAll(){
  if(!realWorld) return; // still connecting (realEnter renders when the fetch lands)
  updateBenchButtons(false); // the empty-tree read-bench guard tracks every state change
  if(viewingRoot){ renderRealView(); return; }
  renderRealPanes(); renderRealRibbon(); renderRealStore(); renderRealStats(); renderRealNarration();
  renderRootLog();
}

/* The annotated hex viewer: every byte painted by the role the ENGINE's own
   parse assigned it (/nodes/{hash}/layout — regions tile the array exactly;
   unattributed bytes are honest 'scaffolding'). The page renders regions and
   never interprets a byte itself — the one-decoder rule. */
const HX_ROLES=['envelope','key','value','address','counts','scaffolding'];
async function renderIbytes(det, hash){
  const dump=det.querySelector('.hexdump');
  if(dump.dataset.done) return;
  dump.dataset.done='1';
  let bytes;
  try{ bytes=await realFetch('/nodes/'+hash+'/bytes'); }
  catch(e){ dump.textContent='(bytes unavailable)'; return; }
  let layout=null;
  try{ layout=await realFetch('/nodes/'+hash+'/layout'); }catch(e){ /* explicit fallback below */ }
  if(!layout){
    dump.textContent=bytes.hex;
    det.querySelector('.ibytes-note').textContent='field layout unavailable — showing the flat dump';
    return;
  }
  const hex=bytes.hex, regions=layout.regions;
  let html='<div class="hx-legend">'+HX_ROLES.map(r=>
    `<span class="hx-lg"><span class="hx-chip hx-${r}"></span>${r}</span>`).join('')+'</div>';
  html+='<div class="hx-grid">';
  regions.forEach((r,ri)=>{
    for(let b=r.start;b<r.end;b++){
      html+=`<span class="hx-b hx-${r.role}" data-ri="${ri}" data-off="${b}">${hex.substr(b*2,2)}</span>`;
    }
  });
  html+='</div><div class="hx-tip">hover a byte or a field — the engine\'s parse named every range</div>';
  html+='<div class="hx-fields">'+regions.map((r,ri)=>
    `<div class="hx-f" data-ri="${ri}"><span class="hx-chip hx-${r.role}"></span>`+
    `[${r.start}‥${r.end}) ${escHtml(r.label)}${r.decoded? ' — '+escHtml(r.decoded):''}</div>`).join('')+'</div>';
  dump.innerHTML=html;
  dump.classList.add('hx');
  const tip=dump.querySelector('.hx-tip');
  const hl=ri=>dump.querySelectorAll('[data-ri]').forEach(x=>x.classList.toggle('hl', x.dataset.ri===ri));
  dump.addEventListener('mouseover', ev=>{
    const tgt=ev.target.closest('[data-ri]');
    if(!tgt) return;
    const r=regions[+tgt.dataset.ri];
    hl(tgt.dataset.ri);
    const off=tgt.dataset.off!==undefined? `offset ${tgt.dataset.off} · ` : '';
    tip.textContent=`${off}${r.role} · ${r.label}${r.decoded? ' — '+r.decoded:''}`;
  });
  dump.addEventListener('mouseleave', ()=>{
    dump.querySelectorAll('.hl').forEach(x=>x.classList.remove('hl'));
    tip.textContent='hover a byte or a field — the engine\'s parse named every range';
  });
}

/* ---------- REAL mode write ops (Step 7): straight to the API ---------- */
/* Two timings, deliberately separate: `engine` is measured SERVER-side around the
   engine work alone; `round-trip` is measured here around the fetch (HTTP + JSON +
   network + the browser). Showing only one would invite the classic misread — a
   slow round-trip blamed on the engine, or engine cost hidden inside wire cost. */
function fmtDur(us){
  if(us==null) return null;
  if(us<1000) return `${us} µs`;
  if(us<1_000_000) return `${(us/1000).toFixed(1)} ms`;
  return `${(us/1_000_000).toFixed(2)} s`;
}
function timingNote(engineMicros, tripMs){
  const eng=fmtDur(engineMicros);
  const trip=fmtDur(Math.round(tripMs*1000));
  return ` <span class="rr-timing" style="color:var(--faint)" title="engine = measured server-side around the engine work only (single-shot — the first op after boot includes JIT warm-up); round-trip = this fetch, incl. HTTP + JSON">`+
    (eng? `engine <b>${eng}</b> · `:'')+`round-trip ${trip}</span>`;
}
async function realMutate(path, method, keys, label){
  readPath=null; // the lit descent described the pre-write tree
  viewingRoot=null; // writes target the head — viewing ends
  realPanel(`${label} …`);
  try{
    const t0=performance.now();
    const resp=await realFetch(path,{method, body:JSON.stringify({keys})});
    const tripMs=performance.now()-t0; // the write's round-trip; the refetch below is display, not the op
    await realLoad(new Set(resp.written));
    selectedKey=null;
    renderRealAll();
    realPanel(`${label} → the engine wrote ${resp.written.length} node${resp.written.length===1?'':'s'} (the spine)`+
      ` · root <span class="rr-hash" data-rh="${resp.rootHash??''}">${resp.rootHash?'⋄'+resp.rootHash.slice(0,10):'∅'}</span>`+
      timingNote(resp.engineMicros, tripMs));
    const h=el('realpanel').querySelector('.rr-hash');
    if(h&&h.dataset.rh) h.onclick=()=>inspectReal(h.dataset.rh);
  }catch(e){ realUnreachable(e); }
}
function doRealInsert(k){
  // D-5 (G-4): the engine's put is an upsert — say so instead of a silent no-op count
  if(realWorld && realLeaves().some(n=>n.entries.some(e=>e.k===k))){
    realPanel(`key ${k} already present — the engine's put is an OVERWRITE (count unchanged); pick a key not on the ribbon`);
    return;
  }
  realMutate('/tree/keys','POST',[k],`insert ${k}`);
}
function doRealDelete(k){ realMutate('/tree/keys','DELETE',[k],`delete ${k}`); }
function doRealRebuild(n){
  const keys=Array.from({length:Math.max(0,n)},(_,i)=>10+10*i);
  realMutate('/tree','PUT',keys,`rebuild: ${keys.length} evenly spaced keys (chunk controls are sim-only — the engine chunks at 512 B–16 KiB)`);
}
function doRealRand(){
  const s=realWorld&&realWorld.stats;
  if(!s||!s.rootHash){ doRealInsert(50); return; }
  const ks=[]; realLeaves().forEach(n=>n.entries.forEach(e=>ks.push(e.k)));
  for(let t=0;t<20 && ks.length>1;t++){
    const i=Math.floor(Math.random()*(ks.length-1));
    const a=ks[i], b=ks[i+1];
    if(b-a>1){ doRealInsert(a+1+Math.floor(Math.random()*(b-a-1))); return; }
  }
  doRealInsert(ks[ks.length-1]+1+Math.floor(Math.random()*9));
}
/** Re-fetch the engine's state without a write — another client/tab may have
    written (the page holds a cache, the backend holds the truth). */
async function realRefresh(){
  if(dataMode!=='real') return;
  try{
    await realLoad(realWorld? realWorld.written : new Set());
    renderRealAll();
  }catch(e){ realUnreachable(e); }
}
el('realrefresh').onclick=()=>realRefresh();
addEventListener('focus',()=>{ if(dataMode==='real' && realWorld) realRefresh(); });

/* REAL store reset: arm-to-confirm (same two-click contract as the sim's erase),
   then POST /api/reset — the ONE way the engine-side store forgets. */
let realResetArmTimer=null;
el('realreset').onclick=()=>{
  const b=el('realreset');
  if(!b.classList.contains('armed')){
    b.classList.add('armed'); b.textContent='Click again to erase';
    realResetArmTimer=setTimeout(()=>{ b.classList.remove('armed'); b.textContent='Reset REAL store'; },3000);
    return;
  }
  clearTimeout(realResetArmTimer);
  b.classList.remove('armed'); b.textContent='Reset REAL store';
  readPath=null;
  realFetch('/reset',{method:'POST'})
    .then(async()=>{ realWorld=null; rootLog=[]; viewingRoot=null;
      await realLoad(new Set()); renderRealAll();
      realPanel('REAL store erased — empty tree, empty store (a fresh engine)'); })
    .catch(e=>realUnreachable(e));
};

/* ---------- REAL mode reads: the engine's own descent, measured ---------- */
async function realReadOp(path, describe){
  try{
    const t0=performance.now();
    const r=await realFetch(path);
    const tripMs=performance.now()-t0;
    readPath={commit:'REAL', set:new Set(r.readPath)};
    renderRealPanes();
    realPanel(describe(r)+
      ` — <b class="rr-cmp">${r.readPath.length} node${r.readPath.length===1?'':'s'} read</b>, lit in the AFTER pane`+
      timingNote(r.engineMicros, tripMs)+
      ` <span style="color:var(--faint)">(the store recorded these reads — measured, not re-derived)</span>`);
  }catch(e){ realUnreachable(e); }
}
function doRealFind(k){
  realReadOp('/tree/find/'+k, r=> r.found
    ? `read key ${r.key}: <b>FOUND</b>`
    : `read key ${r.key}: <b>absent</b> — same descent, the leaf just lacks it`);
}
function doRealRank(n1){ // the input is 1-based like the sim; the API is 0-based
  realReadOp('/tree/rank/'+(n1-1), r=> r.key==null
    ? `seek #${n1}: out of range (tree holds ${realWorld? realWorld.stats.treeCount : '?'} keys)`
    : `seek #${n1} → key <b>${r.key}</b> — subtree counts steered the descent; skipped children were never read`);
}
function doRealScan(a,b){
  realReadOp(`/tree/scan?from=${a}&to=${b}`, r=>
    `scan [${a}‥${b}] → <b>${r.keys.length} keys</b>${r.truncated?' (truncated at the server cap)':''}`+
    (r.keys.length? ` — ${r.keys[0]}…${r.keys[r.keys.length-1]}`:''));
}

/** Bulk random insert — REAL mode only (the sim is toy-scale by design; the
    ENGINE's geometry needs thousands of keys to show). One POST, one flush:
    the whole batch is a single measured write set. Keys are generated unique
    and high (1e6+) so they never collide with the small hand-inserted range —
    treeCount grows by exactly n. */
function doRealBulk(nRaw){
  const n=Math.max(1, Math.min(1_000_000, nRaw|0));
  const ks=new Set();
  while(ks.size<n) ks.add(1_000_000 + Math.floor(Math.random()*9_000_000_000_000));
  realMutate('/tree/keys','POST',[...ks],`insert ${n.toLocaleString('en-US')} random keys`);
}
el('realbulk').onclick=()=>doRealBulk(parseInt(el('bulkn').value,10)||10000);
el('bulk50k').onclick=()=>doRealBulk(50_000);
el('bulk100k').onclick=()=>doRealBulk(100_000);
el('bulk500k').onclick=()=>doRealBulk(500_000);
el('bulk1m').onclick=()=>doRealBulk(1_000_000);

/* ---------- REAL mode benchmark: the loop runs SERVER-side, this only renders ----------
   A browser loop of N fetches would measure HTTP + JSON + the browser, not the engine
   (D-1 of playground-benchmark-section). Latency fields arrive in NANOS (warm point
   reads are sub-µs); the client formats server numbers, never derives them — the one
   client-side computation is the round-trip of the bench call itself. */
function fmtNs(ns){
  if(ns==null) return '–';
  if(ns<1000) return `${ns} ns`;
  if(ns<1_000_000) return `${(ns/1000).toFixed(1)} µs`;
  if(ns<1_000_000_000) return `${(ns/1_000_000).toFixed(1)} ms`;
  return `${(ns/1_000_000_000).toFixed(2)} s`;
}
function updateBenchButtons(running){
  const empty=!realWorld || !realWorld.stats || !realWorld.stats.treeCount;
  el('benchread').disabled = !!running || empty; // empty tree: nothing to look up (D-6)
  el('benchwrite').disabled = !!running;
  el('benchcmp').disabled = !!running || empty;  // nothing to copy either
}
/* The bench/compare surfaces are DECLARATIVELY BOUND (write-path-explorer.bind.js):
   their HTML lives as templates in the .html file; the builders below produce plain
   view-model DATA (every label/style/class string precomputed — templates are
   logic-free by design), and assigning to benchVm re-renders. */
const benchVm = Bind.mount(el('benchout'), {
  showBench:false, showCompare:false, bench:{}, hist:[], showHist:false,
  cmp:{headline:'', panels:[], arms:[], note:''},
});
function buildBenchVm(r, tripMs){
  return {
    big: `${r.opsPerSec.toLocaleString('en-US')} ops/s`,
    headline: ` — ${r.ops.toLocaleString('en-US')} ${r.kind==='read'?'point lookups':'insert+flush ops'}`+
      ` on ${r.treeCount.toLocaleString('en-US')} keys (height ${r.height})`,
    p50: fmtNs(r.p50Nanos),
    strip: ` · p90 ${fmtNs(r.p90Nanos)} · p95 ${fmtNs(r.p95Nanos)} · p99 ${fmtNs(r.p99Nanos)} · max ${fmtNs(r.maxNanos)}`+
      ` · ${r.nodesPerOp} node${r.nodesPerOp===1?'':'s'} ${r.kind==='read'?'read':'written'}/op`,
    restored: r.kind==='write' && !!r.rootRestored,
    notRestored: r.kind==='write' && !r.rootRestored,
    restoredHash: r.rootBefore? '⋄'+r.rootBefore.slice(0,10) : '∅',
    restoredNote: ` — insert+delete of the same keys re-derived identical root bytes (history-independence, live);`+
      ` the ${r.storedNodesDelta} bench spine nodes stay as unreachable garbage in the store`,
    failNote: ` — before ${r.rootBefore} after ${r.rootAfter}; this should be impossible, please report it`,
    note: `engine time only, measured server-side per op (${r.warmupOps} warm-up ops uncounted)`+
      ` · whole call round-trip ${fmtDur(Math.round(tripMs*1000))}`+
      ` · your machine, warm page cache — a teaching measurement, not a product benchmark`,
  };
}
function buildBenchRow(r){
  return { kind: r.kind, ops: r.ops.toLocaleString('en-US'), keys: r.treeCount.toLocaleString('en-US'),
    height: String(r.height), opsPerSec: r.opsPerSec.toLocaleString('en-US'),
    p50: fmtNs(r.p50Nanos), p99: fmtNs(r.p99Nanos), nodesPerOp: String(r.nodesPerOp) };
}
async function runBench(kind){
  const ops = kind==='read'
    ? Math.max(1, Math.min(50_000, parseInt(el('benchreads').value,10)||5000))
    : Math.max(1, Math.min(2_000, parseInt(el('benchwrites').value,10)||500));
  updateBenchButtons(true);
  realPanel(`${kind} benchmark: ${ops.toLocaleString('en-US')} ops running on the engine (the single-user service blocks meanwhile)…`);
  try{
    const t0=performance.now();
    const r=await realFetch('/bench',{method:'POST', body:JSON.stringify({kind, ops})});
    const tripMs=performance.now()-t0;
    benchVm.bench = buildBenchVm(r, tripMs);
    benchVm.hist = [...benchVm.hist, buildBenchRow(r)]; // replace, don't mutate (shallow reactivity)
    benchVm.showHist = benchVm.hist.length > 1;
    benchVm.showBench = true; benchVm.showCompare = false;
    el('benchout').hidden = false;
    if(kind==='write'){ await realLoad(realWorld? realWorld.written : new Set()); renderRealAll(); } // storedNodes grew
    realPanel(`${kind} bench done — ${r.opsPerSec.toLocaleString('en-US')} ops/s, results below`);
  }catch(e){ realUnreachable(e); }
  finally{ updateBenchButtons(false); }
}
el('benchread').onclick=()=>runBench('read');
el('benchwrite').onclick=()=>runBench('write');

/* The three-store comparison: fresh memory/file/rocks arms server-side, each seeded
   with a byte copy of the current tree (identical root per arm = proof of equality),
   both benches per arm. The client renders the server's table — nothing computed here. */
const CMP_LABELS={memory:'memory (control — no disk)', file:'file (chunk per file)', rocks:'RocksDB (log-structured)'};
function buildCompareVm(r, tripMs){
  // bar-chart view-model: throughput panels (longer = faster) and layered latency
  // panels (solid p50 · mid p95 · pale p99, shorter = faster — the INVERTED reading
  // keeps its own hue via the class strings below). Each panel scales to its own
  // extreme; the max is a TICK on the p99 scale — a single straggler is often 50-200x
  // the p99 and would squash every bar to a sliver, so an off-scale max clips to the
  // edge with the value + multiplier in its tooltip. All of that logic lives HERE, in
  // data — the templates only stamp {cls, style, title} bars.
  const PANELS=[
    {metric:'read',      lat:false, title:'reads — ops/s (longer = faster; scaled to this panel’s fastest)',  b:a=>a.read},
    {metric:'read-lat',  lat:true,  title:'reads — latency: solid p50 · mid p95 · pale p99 · ▏max (shorter = faster; scaled to the slowest p99)',  b:a=>a.read},
    {metric:'write',     lat:false, title:'writes — ops/s (longer = faster; scaled to this panel’s fastest)', b:a=>a.write},
    {metric:'write-lat', lat:true,  title:'writes — latency: solid p50 · mid p95 · pale p99 · ▏max (shorter = faster; scaled to the slowest p99)', b:a=>a.write},
  ];
  const panels = PANELS.map(m=>{
    const max=Math.max(...r.arms.map(a=>m.lat? m.b(a).p99Nanos : m.b(a).opsPerSec), 1);
    const w=(v)=>Math.max(0.8, v/max*100).toFixed(1);
    return { metric:m.metric, title:m.title, rows:r.arms.map(a=>{
      const b=m.b(a), lab=`${a.kind}${a.kind===r.liveKind?' ◂':''}`;
      if(!m.lat) return { lab, val:b.opsPerSec.toLocaleString('en-US'),
        bars:[{cls:'cmpbar', arm:a.kind, title:null, style:`width:${w(b.opsPerSec)}%`}] };
      const maxPct=b.maxNanos/max*100, clipped=maxPct>100;
      return { lab, val:`${fmtNs(b.p50Nanos)} ⋯ ${fmtNs(b.p99Nanos)}`, bars:[
        {cls:'cmpbar lat tail', arm:a.kind, title:`p99 ${fmtNs(b.p99Nanos)}`, style:`width:${w(b.p99Nanos)}%`},
        {cls:'cmpbar lat p95',  arm:a.kind, title:`p95 ${fmtNs(b.p95Nanos)}`, style:`width:${w(b.p95Nanos)}%`},
        {cls:'cmpbar lat p50',  arm:a.kind, title:`p50 ${fmtNs(b.p50Nanos)}`, style:`width:${w(b.p50Nanos)}%`},
        {cls:clipped?'cmpmax clipped':'cmpmax', arm:a.kind, style:`left:${Math.min(maxPct,99.2).toFixed(1)}%`,
         title:`max ${fmtNs(b.maxNanos)}${clipped?' — beyond this scale ('+(maxPct/100).toFixed(1)+'x the slowest p99)':''}`},
      ]};
    })};
  });
  const arms = r.arms.map(a=>({ kind:a.kind,
    label:`${CMP_LABELS[a.kind]??a.kind}${a.kind===r.liveKind?' ← this service':''}`,
    seed:`${a.seedMillis} ms`,
    readOps:a.read.opsPerSec.toLocaleString('en-US'), readP50:fmtNs(a.read.p50Nanos), readP99:fmtNs(a.read.p99Nanos),
    writeOps:a.write.opsPerSec.toLocaleString('en-US'), writeP50:fmtNs(a.write.p50Nanos),
    ok:!!(a.sameRoot && a.write.rootRestored), bad:!(a.sameRoot && a.write.rootRestored) }));
  return {
    headline:` — ${r.treeCount.toLocaleString('en-US')} keys (height ${r.height}), byte-copied into three fresh stores;`+
      ` benches: ${r.arms[0].read.ops.toLocaleString('en-US')} reads + ${r.arms[0].write.ops.toLocaleString('en-US')} writes each`,
    panels, arms,
    note:`each arm re-derived root ⋄${r.rootHash.slice(0,10)} from the copied chunks and restored it after its write bench`+
      ` — content addressing as the equality proof · arms run sequentially in one JVM (order effects possible; warm-ups uncounted)`+
      ` · whole comparison round-trip ${fmtDur(Math.round(tripMs*1000))}`+
      ` · your machine, warm page cache — a teaching measurement, not a product benchmark`,
  };
}
async function runCompare(){
  const readOps = Math.max(1, Math.min(50_000, parseInt(el('benchreads').value,10)||5000));
  const writeOps = Math.max(1, Math.min(2_000, parseInt(el('benchwrites').value,10)||500));
  updateBenchButtons(true);
  realPanel(`comparing stores: copying your tree into fresh memory/file/rocks stores, then ${readOps.toLocaleString('en-US')} reads + ${writeOps.toLocaleString('en-US')} writes on each (the single-user service blocks meanwhile)…`);
  try{
    const t0=performance.now();
    const r=await realFetch('/bench/compare',{method:'POST', body:JSON.stringify({readOps, writeOps})});
    const tripMs=performance.now()-t0;
    benchVm.cmp = buildCompareVm(r, tripMs);
    benchVm.showCompare = true; benchVm.showBench = false;
    el('benchout').hidden = false;
    realPanel(`store comparison done — three byte-equal arms, results below`);
  }catch(e){ realUnreachable(e); }
  finally{ updateBenchButtons(false); }
}
el('benchcmp').onclick=()=>runCompare();

/** Controls with no REAL-mode meaning (no backend surface for them). Disabled on
    entry; setDataMode('sim') re-enables and lets syncButtons re-own the rest. */
const SIM_ONLY_CONTROLS=['update','racebtn','stress','batchtoggle',
  'mergebtn','mkbranch','mktag','corruptbtn','verifybtn','resetdb','exportbtn','importbtn','rungc','converge'];
function syncRealButtons(){
  el('insert').disabled=false;
  el('delete').disabled = selectedKey==null;
  el('delete').textContent = selectedKey==null? 'Delete' : `Delete ${selectedKey}`;
  el('randop').disabled=false;
  for(const id of SIM_ONLY_CONTROLS){ const b=el(id); if(b) b.disabled=true; }
}

// shared write controls route by mode (later assignment wins over the top-of-file wiring)
el('rebuild').onclick=()=>{ if(dataMode==='real'){ doRealRebuild(+el('nkeys').value||0); } else freshBuild(); };
el('startempty').onclick=()=>{
  if(dataMode==='real'){ doRealRebuild(0); return; }
  el('nkeys').value=0; el('nval').textContent='0'; freshBuild(); };
el('insert').onclick=()=>{ const v=parseInt(el('keyin').value,10);
  if(!Number.isFinite(v)){ hint('type an integer key first'); return; }
  if(dataMode==='real') doRealInsert(v); else doInsert(v);
  el('keyin').value=''; };
el('delete').onclick=()=>{ if(selectedKey==null) return;
  if(dataMode==='real') doRealDelete(selectedKey); else doDelete(selectedKey); };
el('randop').onclick=()=>{ if(dataMode==='real') doRealRand();
  else randomOp(mulberry(fnv('rop'+(rngCounter++)+el('seed').value))); };

el('readbtn').onclick=()=>{ const v=parseInt(el('readkey').value,10);
  if(!Number.isFinite(v)){ hint('type an integer key to read'); return; }
  if(dataMode==='real') doRealFind(v); else doRead(v); };
el('idxbtn').onclick=()=>{ const v=parseInt(el('idxin').value,10);
  if(!Number.isFinite(v)){ hint('type an index (1-based) to seek'); return; }
  if(dataMode==='real') doRealRank(v); else doSeek(v); };
el('scanbtn').onclick=()=>{ const a=parseInt(el('scanfrom').value,10), b=parseInt(el('scanto').value,10);
  if(!Number.isFinite(a)||!Number.isFinite(b)){ hint('type both range ends'); return; }
  if(a>b){ hint('from must be ≤ to'); return; }
  if(dataMode==='real') doRealScan(a,b); else doScan(a,b); };

el('mode-sim').onclick=()=>{ if(dataMode!=='sim') setDataMode('sim'); };
el('mode-shadow').onclick=()=>{ if(dataMode!=='shadow') setDataMode('shadow'); };
el('mode-real').onclick=()=>{ if(dataMode!=='real') setDataMode('real'); };

{ // one-time nudge: this page is being SERVED by the real engine — connect the dots
  let dismissed=null; try{ dismissed=localStorage.getItem('wp-real-nudge'); }catch(e){}
  const served=location.protocol.startsWith('http');
  if(served && dismissed!=='1'){
    el('realnudge').hidden=false;
    el('nudgex').onclick=()=>{ el('realnudge').hidden=true;
      try{ localStorage.setItem('wp-real-nudge','1'); }catch(e){} };
  }
}

{ // funnel: every world change flows through renderAll, routed by mode
  const _renderAll=renderAll;
  renderAll=function(){
    if(dataMode==='real'){ renderRealAll(); return; }
    _renderAll(); if(realOn) queueMicrotask(realSync);
  };
  let saved=null;
  try{ saved=localStorage.getItem('wp-data-mode'); }catch(e){}
  if(saved==='shadow'||saved==='real') setDataMode(saved);
}
