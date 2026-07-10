/*
 * write-path-explorer.app.js — application services: IndexedDB persistence +
 * CAS-first boot reconstruction, the scale card and size histogram, the
 * guided tour, the theme toggle, and the boot sequence. Loads LAST.
 */
/* ---------- durable NodeStore: IndexedDB as the disk under the pool ----------
   The in-memory POOL stays the synchronous working set (the "memtable"); every
   commit write-behinds its chunks as individual hash-keyed records — open
   devtools → Application → IndexedDB to SEE the content-addressed store, rows
   appearing on writes and vanishing on sweeps. Boot = reopen the database:
   read the meta (the manifest), rehydrate the pool, resume. Best-effort: a
   sandboxed context falls back to memory-only. */
const IDB_NAME='wp-explorer', CHUNKS='chunks', META='meta';
let idb=null, persistOK=false;
function idbOpen(){ return new Promise(res=>{ try{
  const rq=indexedDB.open(IDB_NAME,1);
  rq.onupgradeneeded=()=>{ rq.result.createObjectStore(CHUNKS); rq.result.createObjectStore(META); };
  rq.onsuccess=()=>res(rq.result); rq.onerror=()=>res(null); rq.onblocked=()=>res(null);
}catch(e){ res(null); } }); }
// Durability signal: body[data-idb-pending] counts open write transactions.
// Persistence is fire-and-forget, so "the DOM shows it" ≠ "IndexedDB has it" —
// a reload can beat the transaction commit and lose the last write (this raced
// a real spec flake). 0 = everything the page did is durably stored.
let idbPending=0;
function idbTrack(tx){
  idbPending++; document.body.dataset.idbPending=String(idbPending);
  tx.oncomplete=tx.onerror=tx.onabort=()=>{ idbPending--; document.body.dataset.idbPending=String(idbPending); };
}
function idbPut(st,k,v){ if(!persistOK) return; try{ const tx=idb.transaction(st,'readwrite'); idbTrack(tx); tx.objectStore(st).put(v,k); }catch(e){} }
function idbClear(st){ if(!persistOK) return; try{ const tx=idb.transaction(st,'readwrite'); idbTrack(tx); tx.objectStore(st).clear(); }catch(e){} }
function idbGet(st,k){ return new Promise(res=>{ if(!persistOK) return res(null); try{
  const rq=idb.transaction(st).objectStore(st).get(k);
  rq.onsuccess=()=>res(rq.result??null); rq.onerror=()=>res(null); }catch(e){ res(null); } }); }
function idbAllPairs(st){ return new Promise(res=>{ if(!persistOK) return res([]); try{
  const os=idb.transaction(st).objectStore(st);
  const kq=os.getAllKeys(), vq=os.getAll();
  vq.onsuccess=()=>{ if(kq.result) res(kq.result.map((k,i)=>[k,vq.result[i]])); };
  vq.onerror=()=>res([]); }catch(e){ res([]); } }); }

function idbDelete(){ return new Promise(res=>{ try{
  if(idb){ idb.close(); idb=null; }
  const rq=indexedDB.deleteDatabase(IDB_NAME);
  rq.onsuccess=()=>res(true); rq.onerror=()=>res(false); rq.onblocked=()=>res(true);
}catch(e){ res(false); } }); }

function persistMeta(){
  if(!persistOK || !commits.length) return;
  // CAS-first persistence: the content lives in the chunk store; the meta is
  // only the MUTABLE surface — refs by hash, a reflog (ordered commit hashes,
  // labels kept so swept commits still display as ghosts), selection, and the
  // world config. Everything else is RECONSTRUCTED from the objects at boot.
  idbPut(META,'state',{
    v:3, // v3: internal-node preimages carry the subtree-count vector (counted B-tree)
    log:commits.map(c=>c.chash), // order only — the message lives in the commit object
    branches:[...branches].map(([n,id])=>[n, commits[id]? commits[id].chash : null]),
    tags:[...tags],
    currentBranch,
    selectedChash: cur()? cur().chash : null,
    cfg: commits[0]? commits[0].cfg : {avg:+el('avg').value, seed:+el('seed').value},
    nkeys:+el('nkeys').value,
    naiveBytes,
  });
}

/** Read a tree's leaf entries straight out of the CAS (the tree IS the data). */
function entriesFromTree(rootHash){
  if(!rootHash || !POOL.has(rootHash)) return [];
  const out=[];
  lensLevelNodes(rootHash,0).forEach(n=>{ if(n.entries) out.push(...n.entries.map(e=>({...e}))); });
  return out;
}
function persistCommitChunks(c){
  if(!persistOK) return;
  c.written.forEach(h=>{ const n=POOL.get(h); if(n) idbPut(CHUNKS,h,n); });
  if(c.chash) idbPut(CHUNKS,c.chash,POOL.get(c.chash));
}
function persistFullStore(){
  if(!persistOK) return;
  idbClear(CHUNKS);
  POOL.forEach((n,h)=>idbPut(CHUNKS,h,n));
  persistMeta();
}
/** Reconstruct the runtime view FROM THE OBJECTS: parents resolved by hash,
    entries read from the tree, written sets recomputed as reach-diffs. A commit
    whose objects are gone materializes as a pruned GHOST — only its name
    survives, in the reflog (git's model exactly). Shared by boot AND pack
    import. */
function rebuildViewFromLog(logHashes, cfg){
  const idx=new Map();
  commits=[];
  let ghosts=0;
  logHashes.forEach((chash,i)=>{
    const co=POOL.get(chash);
    if(!co || !co.commit){
      ghosts++;
      commits.push({id:i, label:'(swept commit)', rootHash:null, parent:-1, parent2:null,
        entries:[], written:new Set(), total:0, height:0, touchedKey:null,
        touchedKeys:null, pruned:true, cfg, chash, ref:null});
      return;
    }
    const rootOk = co.root==null ||
      (POOL.has(co.root) && [...reach(co.root)].every(h=>POOL.has(h)));
    const parent = co.parents[0]!=null? (idx.get(co.parents[0]) ?? -1) : -1;
    const parent2 = co.parents[1]!=null? (idx.get(co.parents[1]) ?? null) : null;
    const r=rootOk? reach(co.root) : new Set();
    const pr=(parent>=0 && commits[parent].rootHash)? reach(commits[parent].rootHash) : new Set();
    commits.push({id:i, label:co.message??co.label, rootHash:rootOk? co.root:null,
      parent, parent2,
      entries: rootOk? entriesFromTree(co.root) : [],
      written:new Set([...r].filter(h=>!pr.has(h))),
      total:r.size, height:rootOk? treeHeight(co.root):0,
      touchedKey:null, touchedKeys:null, pruned:!rootOk, cfg,
      chash, ref:null});
    idx.set(chash, i);
  });
  // banding: adopt this view's ids where the chunks carry no birth yet
  commits.forEach(c=>{
    c.written.forEach(h=>{ const n=POOL.get(h); if(n && n.bornAt==null) n.bornAt=c.id; });
    const cc=POOL.get(c.chash); if(cc && cc.bornAt==null) cc.bornAt=c.id;
  });
  return {idx, ghosts};
}

let formatWipeMsg=null; // set when boot wipes a pre-v3 world; shown AFTER freshBuild's own narration

async function persistBoot(){
  idb=await idbOpen(); persistOK=!!idb;
  const note=el('persistnote');
  if(note) note.textContent= persistOK? '· persisted to IndexedDB — reloads keep your history'
                                      : '· memory-only (browser storage unavailable here)';
  if(!persistOK) return false;
  const meta=await idbGet(META,'state');
  if(meta && meta.v && meta.v!==3){
    // A pre-v3 world: its internal chunks lack the subtree-count vector, so
    // their bytes can no longer hash to their names. Pre-1.0 in miniature —
    // the reader requires the new shape; the old world is wiped, not migrated.
    idbClear(CHUNKS); idbClear(META);
    formatWipeMsg='<b>format changed</b> — internal nodes now carry the subtree-count '+
      'vector in their bytes, so every internal NAME changed with them (a name is a checksum). '+
      'The old world could not be reinterpreted and was cleared; a fresh world was built below — '+
      'the pre-1.0 rule, enacted: no defensive readers, evolve the format freely, rebuild.';
    return false;
  }
  if(!meta || meta.v!==3 || !meta.log || !meta.log.length) return false;
  try{
    const rows=await idbAllPairs(CHUNKS);
    rows.forEach(([h,n])=>POOL.set(h,n));
    const cfg=meta.cfg||{avg:5,seed:7};
    const {idx, ghosts}=rebuildViewFromLog(
      meta.log.map(l=> typeof l==='string'? l : l.chash), cfg);
    branches=new Map((meta.branches||[]).map(([n,ch])=>[n, idx.get(ch) ?? 0]));
    tags=new Map(meta.tags||[]);
    currentBranch=meta.currentBranch;
    selected= idx.get(meta.selectedChash) ?? commits.length-1;
    if(commits[selected].pruned) selected=commits.findIndex(c=>!c.pruned);
    if(selected<0) return false;
    naiveBytes=meta.naiveBytes||0;
    el('nkeys').value=meta.nkeys??32; el('nval').textContent=el('nkeys').value;
    el('avg').value=String(cfg.avg); el('seed').value=cfg.seed;
    renderAll();
    // the boot narration is a SIM message — in REAL mode (restored before this async
    // boot completes) it would clobber the REAL banner; stash it there instead so
    // returning to sim still shows it (the disk-engine e2e caught the race live)
    const bootMsg=`<b>session reconstructed</b> from the object store — refs + reflog were the only state; `+
      `${commits.length-ghosts} commit${commits.length-ghosts===1?'':'s'} rebuilt by walking the Merkle objects`+
      (ghosts? `, ${ghosts} swept ghost${ghosts===1?'':'s'} remembered by the reflog alone`:'')+
      `. The database IS the chunks; everything else was derived.`;
    if(typeof dataMode!=='undefined' && dataMode==='real') simNarration=bootMsg;
    else el('simnarr').innerHTML=bootMsg;
    return true;
  }catch(e){ console.error('restore failed',e); return false; }
}

/* ---------- scale card: the toy's math at engine parameters ---------- */
function renderScale(){
  const e2=+el('scalen').value;
  const n=Math.pow(10,e2);
  const F=100, CH=4096;
  const height=Math.max(1, Math.ceil(Math.log(n)/Math.log(F)));
  const chunks=Math.ceil(n/F * F/(F-1))+1;
  const writeChunks=height+1;
  const treeBytes=chunks*CH, writeBytes=writeChunks*CH;
  const fmtB=b=> b>=1e12? (b/1e12).toFixed(1)+' TB' : b>=1e9? (b/1e9).toFixed(1)+' GB'
             : b>=1e6? (b/1e6).toFixed(1)+' MB' : (b/1e3).toFixed(1)+' KB';
  const pct=(writeBytes/treeBytes*100);
  el('scaleout').innerHTML=
    `n = <b class="big">10<sup>${e2}</sup></b> keys (${n.toLocaleString('en-US')})<br>`+
    `tree height <b>${height}</b> → point read = <b>${height} fetches</b><br>`+
    `one-key write ≈ <b>${writeChunks} chunks ≈ ${fmtB(writeBytes)}</b><br>`+
    `tree ≈ ${chunks.toLocaleString('en-US')} chunks ≈ ${fmtB(treeBytes)} — a write rewrites <b>${pct<0.001? pct.toExponential(1): pct.toFixed(3)}%</b> of it`;
}
el('scalen').addEventListener('input',renderScale);
renderScale();

/* ---------- chunk-size histogram: measured vs the geometric expectation ---------- */
function renderHist(){
  const svg=d3.select('#hist'); svg.selectAll('*').remove();
  const cfg=commits.length? cur().cfg : {avg:+el('avg').value, seed:+el('seed').value};
  const sp=new Splitter(cfg.avg, buzTable(cfg.seed));
  const MINB=sp.MINB, MAXB=sp.MAXB, T=sp.T;
  // sample: every leaf chunk EVER minted (still in the pool), measured in stream bytes
  const sizes=[];
  for(const n of POOL.values()){
    if(n.level!==0 || !n.entries) continue;
    let b=0; for(const e2 of n.entries) b+=entryBytes(e2).length;
    sizes.push(Math.min(b,MAXB));
  }
  const liveLeaves= commits.length&&cur().rootHash? lensLevelNodes(cur().rootHash,0) : [];
  const fill= liveLeaves.length? (liveLeaves.reduce((a,n)=>a+n.count,0)/liveLeaves.length) : 0;
  el('histnote').textContent=`${sizes.length} leaf chunks sampled from the store · trigger 1/${T} per byte after the ${MINB}-byte min-zone · cap at ${MAXB} B`+
    (fill? ` · avg live leaf ${fill.toFixed(1)} keys (target ≈ ${cfg.avg})`:'');
  if(!sizes.length) return;
  const BW=4; // bucket width in bytes
  const b0=Math.floor(MINB/BW), b1=Math.ceil(MAXB/BW);
  const buckets=new Array(b1-b0+1).fill(0);
  sizes.forEach(v=>{ buckets[Math.min(Math.max(Math.floor(v/BW)-b0,0),buckets.length-1)]++; });
  // geometric expectation, scaled to the sample: P(len = MINB+j) = (1-p)^j p, cap mass at MAXB
  const pTrig=1/T;
  const exp=new Array(buckets.length).fill(0);
  for(let len=MINB; len<=MAXB; len++){
    const j=len-MINB;
    const prob= len===MAXB? Math.pow(1-pTrig,j) : Math.pow(1-pTrig,j)*pTrig;
    exp[Math.min(Math.floor(len/BW)-b0,exp.length-1)]+=prob*sizes.length;
  }
  const W=(svg.node().clientWidth||420)-16, H=104, pad=6, base=H-16;
  svg.attr('viewBox',`0 0 ${W+16} ${H}`);
  const maxY=Math.max(...buckets,...exp,1);
  const x=i=>pad+i*((W-pad)/buckets.length), bw=(W-pad)/buckets.length;
  const y=v=>base-(base-10)*(v/maxY);
  const g=svg.append('g');
  g.selectAll('rect.hbar').data(buckets).join('rect').attr('class','hbar')
    .attr('x',(d,i)=>x(i)).attr('width',Math.max(bw-1,1))
    .attr('y',d=>y(d)).attr('height',d=>base-y(d))
    .append('title').text((d,i)=>`${(b0+i)*BW}–${(b0+i+1)*BW-1} B: ${d} chunks`);
  g.append('path').attr('class','hexp')
    .attr('d','M'+exp.map((v,i)=>`${x(i)+bw/2},${y(v)}`).join('L'));
  g.append('text').attr('x',x(0)).attr('y',H-3).text(MINB+' B (min)');
  g.append('text').attr('x',x(buckets.length-1)-30).attr('y',H-3).text(MAXB+' B (cap)');
}

/* ---------- guided tour: a stop-per-concept curriculum driving the live controls
   (the card shows n/total dynamically — keep prose count-free, it rots) ---------- */
const TOUR=[
 {t:'welcome', f:'header',
  b:'This is the prolly-tree engine, live: a content-addressed, probabilistically balanced B-tree — the substrate under versioned databases. The stops walk writes, reads, scans, history, merging, the object model, durability, integrity — and finally the REAL engine behind this page. Nothing is a mockup — every stop drives the controls you can, and stays live behind this card.',
  look:['the <b>left rail</b>: build config, reads, writes, the legend',
        'the <b>sticky narration</b> + the <b>stats gauges</b> right under it',
        'the <b>two tree panes</b> with the chunk inspector beside them',
        'the <b>commit graph</b> on top — identity is the content hash (⋄)'],
  try:'the tour just rebuilt a fresh 32-key world — scroll freely; the card follows.',
  deep:'companion docs, beside this file: treemutator-write-path.md + class-roles.md + cursor-read-path.md',
  run:()=>{ el('nkeys').value=32; el('nval').textContent='32'; freshBuild(); }},
 {t:'two trees, one store', f:'#panes',
  b:'A chunk\'s name IS the hash of its bytes — content addressing. Identical subtrees are stored once and shared by reference, no matter which version references them. That single fact powers everything else you will see.',
  look:['node labels are <b>hash prefixes</b> — content addresses, not assigned ids',
        'dim chunks appear in BOTH panes: one copy, two trees',
        'only <b style="color:var(--new)">amber</b> chunks differ between versions',
        'the node store below counts every chunk exactly once'],
  try:'click any chunk — the inspector shows its address, who minted it, and every commit that can reach it.',
  deep:'NodeStore is a flat hash→bytes map; truncated SHA-512 in the engine (FNV-64 here, footer)'},
 {t:'a write is a spine', f:'#readbar',
  b:'We just inserted one key. The write rebuilt only the root→leaf spine through the edit plus a bounded resync neighborhood — the trace under the AFTER pane replayed it, veiled chunks revealing bottom-up as each was emitted.',
  look:['<b>skip rows</b>: identical hashes, never touched',
        '<b>re-chunk rows</b>: the rebuilt leaf, then each parent up the spine',
        '<b>boundary realigned</b>: the suffix resyncs and is shared again',
        'the finale: <b>commit minted</b>, then the manifest <b style="color:var(--new)">pointer swap</b> — the only mutation in the system'],
  try:'scrub with ⏮ ⏭ — every trace also lands in the operation journal below the panes.',
  deep:'TreeMutator.applyMutations + synchronize-then-skip (ADR-0068); O(log n) chunks per write',
  run:()=>{ const g=document.querySelectorAll('#ribbon .gap:not([disabled])'); if(g.length) g[Math.floor(g.length/2)].click(); }},
 {t:'the gauges', f:'#statsrow',
  b:'Summary before detail: the stats row reads the write you just made. Chunks AND bytes written (preimage lengths — the real cost currency), the share ratio, and a sweepable gauge computed from the SAME keep-set as the sweep button — it predicts the deletion count exactly.',
  look:['<b>bytes written</b>: a small fraction of the tree after any single edit',
        '<b>sweepable</b>: the old spine just became garbage — the gauge counts it',
        'the chart: written-per-op bars stay flat while the grey <b>tree size</b> line climbs — that contrast IS the O(log n) claim'],
  try:'change the GC policy dropdown in the store panel — the gauge re-computes live.',
  deep:'the gauge and the sweep share computeKeepSet(); they cannot disagree by construction'},
 {t:'the leaf ribbon', f:'#ribbonwrap',
  b:'Every key in order, boxed by chunk — the payload layer laid flat. Everything above the leaves is routing.',
  look:['<b style="color:var(--new)">amber boxes</b>: chunks this commit wrote',
        'each box\'s right-edge tick says WHY its boundary exists: <b style="color:var(--new)">trigger</b> / <b style="color:var(--orph)">cap</b> / hollow tail',
        'hover a box — its leaf lights up in the tree (and back)',
        'the <b>+ gaps</b> are insert targets; boundary gaps sit BETWEEN boxes'],
  try:'insert far from the last edit and compare the two write traces in the journal — cost is position-independent.',
  deep:'the ticks come from an exact isolated splitter replay — valid because the splitter resets per chunk'},
 {t:'the boundary lens', f:'#lenswrap',
  b:'THE mechanism: a rolling hash slides over the serialized bytes; a boundary fires where it hits the trigger. Boundaries depend only on LOCAL content — why edits stay local, and why histories converge.',
  look:['the character row IS the serialized leaf stream',
        'bar height = closeness to the trigger; full <b style="color:var(--new)">amber</b> fires',
        '<b>min-zone bands</b>: triggers suppressed right after a boundary',
        'ticks marked <b style="color:var(--new)">born</b>/kept/<b style="color:var(--orph)">died</b> vs the parent'],
  try:'hover a byte — its 8-byte window lights up and the readout shows the live hash; click a bar to inspect its chunk; L1 shows the SAME splitter rolling over child hashes.',
  deep:'RollingHashSplitter: 67-byte window / 512 B min / 16 KiB cap in the engine (8 B / 10 B / 3× here); ADR-0069'},
 {t:'a read is a descent', f:'#readbar',
  b:'A point lookup just ran: fetch the root by hash, compare child maxKeys, descend, scan the leaf — O(height) fetches, everything else cold. Every fetch re-hashes its bytes against its name on the way.',
  look:['the <b style="color:var(--shared)">cyan path</b> — everything the lookup touched',
        'per-comparison rows: skip right / descend',
        '<b>chunks never touched</b> in the summary — the routing layer\'s point',
        'each fetch logs <b>re-hashed ✓</b> — verification below the cache'],
  try:'read a missing key: absence proven by the sorted scan. Read 99999: ONE fetch. Select an old commit and read again: snapshot reads, no locks.',
  deep:'Cursor.atKey\'s seek rule; the root hash alone pins the snapshot',
  run:()=>{ const es=cur().entries; if(es.length) doRead(es[Math.floor(es.length/2)].k); }},
 {t:'scans and ordinals', f:'#readbar',
  b:'The range scan just ran: ONE descent, then leaf hops via the parent stack, ending on the STOP PREDICATE — the first key past the end stops the walk with no foreknowledge of the last key. Over ordered keys, a prefix IS exactly such a range.',
  look:['<b>skip its whole subtree</b> rows during the descent',
        '<b>hop right</b>: ascend, advance one child, descend — only NEW nodes count',
        'the <b>STOP</b> row: leaves right of it were never touched',
        'the verdict: keys returned · fetches spent'],
  try:'then try Seek Nth: the subtree-count vector answers "how many keys below?" without reading one — and an out-of-range index is refused by the root alone (size queries are O(1)).',
  deep:'TreeIter\'s stop predicate; the count vector is why internal nodes carry counts',
  run:()=>{ const es=cur().entries; if(es.length>8) doScan(es[2].k, es[Math.floor(es.length*0.6)].k); }},
 {t:'batch: one commit, many edits', f:'#strip',
  b:'Five mutations just landed as ONE commit — history grew by one node. Real systems stage many edits and land them atomically; the write trace shows several rebuilt zones under a single mint.',
  look:['the commit graph: exactly one new node (sized by its write cost)',
        'the write trace: skip runs INTERLEAVED with rebuilt zones',
        'several touched keys highlighted in the ribbon'],
  try:'open Batch… yourself: one op per line (k, k=v, -k), a live staged tally, and per-line errors that block the whole batch — atomicity as UI.',
  deep:'DocumentBatch: stage put/create/patch/delete, land as one commit, read-your-own-writes inside',
  run:()=>{ el('batchfill').click(); el('batchcommit').click(); }},
 {t:'key blame', f:'#inspectorcard',
  b:'We updated one key and selected it — the inspector now shows its HISTORY: every commit that changed it, walking the ancestry. Blame is repeated diff, and diffs are cheap because unchanged subtrees hash-skip.',
  look:['rows newest-first: created v1 → each change since',
        'commit chips are clickable — time-travel WHILE keeping the key inspected',
        'the value shown is the snapshot value at whatever commit you visit'],
  try:'click the oldest chip: the tree, ribbon, and value all rewind together.',
  deep:'per-key history in an upstream document store is exactly this walk (blame / history endpoints)',
  run:()=>{ const es=cur().entries; if(!es.length) return; const k=es[0].k;
            doUpdate(k); selectedKey=k; syncButtons(); renderRibbon(); renderKeyBlame(k); }},
 {t:'branches are the manifest', f:'#striprow',
  b:'We branched \'side\' from an older commit and wrote on it — a new lane. A ref is a name→commit-hash pointer in the manifest, the ONLY mutable table anywhere. Creating or deleting a ref touches zero chunks.',
  look:['the <b>side</b> chip on its own lane; <b style="color:var(--new)">amber chip</b> = checked out',
        'the store did NOT grow when the branch was created',
        'a write advances the checked-out chip — the pointer swap, visible'],
  try:'click chips to check out; × deletes a ref (never the checked-out one, never any chunks).',
  deep:'Manifest maps (repoId, refName) → root hash — the port\'s "only mutable part"',
  run:()=>{ const old=Math.max(0,commits.length-3); selected=old; selectedKey=null; currentBranch=null;
            renderAll(); el('branchname').value='side'; el('mkbranch').click();
            const g=document.querySelectorAll('#ribbon .gap:not([disabled])'); if(g.length) g[g.length-1].click(); }},
 {t:'merge', f:'#readbar',
  b:'Three-way merge against the common ancestor: keys identical on both sides are never compared individually — skipped wholesale by subtree hash. Divergent keys get decisions; both-changed keys surface as CONFLICTS with the policy stated out loud.',
  look:['the aggregate row: <b>N keys identical — never compared</b>',
        'one-sided rows: take theirs / keep ours',
        '<b style="color:var(--orph)">CONFLICT</b> rows with the resolution named',
        'the result: a <b>two-parent commit</b> — the dashed edge'],
  try:'note \'side\' still points where it was — merging never moves the other ref.',
  deep:'MergeEngine recurses only where BOTH sides diverged; findLCA\'s criss-cross caveat applies',
  run:()=>{ checkout('main'); el('mergesel').value='side'; el('mergebtn').click(); }},
 {t:'diff any two commits', f:'#panes',
  b:'Shift-click pinned an old commit as the DIFF BASE. The walk descends the selected tree and skips every subtree whose hash the base already holds — UNREAD. Diff costs O(changed), never O(n): the payoff the architecture was built for.',
  look:['the <b style="color:var(--shared)">dashed cyan ring</b> on the pinned base',
        'panes + ribbon recolored vs the BASE; added keys underlined in the ribbon',
        '<b>skip … UNREAD</b> rows, then key-level +/−/~ rows and the Δ summary'],
  try:'shift-click other commits as base; a second shift-click on the base unpins.',
  deep:'DiffEngine walks only where hashes differ; versioned queries are diffs underneath',
  run:()=>{ diffBase=0; renderStrip(); renderTrees(); renderRibbon(); startDiffTrace(commits[0], cur()); }},
 {t:'tags: immutable pins', f:'#striprow',
  b:'We just minted tag ⊙v1 — the fourth object kind (blob/tree/commit/tag). Unlike a branch, a tag is itself a content-addressed OBJECT: it can never move, only its name can be deleted, and it retains its commit through EVERY sweep policy.',
  look:['the green dashed <b>⊙v1</b> chip on the graph',
        'a green tag tile appeared in the store band — it IS a chunk',
        'the sweepable gauge respects it: the pinned commit never counts as garbage'],
  try:'click the tag tile in the store — the inspector shows its pinned-commit Merkle link.',
  deep:'delete the name → the object lingers until a sweep no longer finds it',
  run:()=>{ diffBase=null; renderAll(); el('branchname').value='v1'; el('mktag').click(); }},
 {t:'everything is an object', f:'#storewrap',
  b:'The store is the system\'s ledger — and EVERYTHING lives in it: tree chunks, ⋄ commit objects (root + parents + message, hashed together — rewriting any ancestor would change every descendant), and ⊙ tags. One pool, one addressing scheme, stored once.',
  look:['bands per commit, each led by its <b>⋄ commit object</b>',
        'the commit message lives IN the object — hashed content, stored nowhere else',
        'the dedup stat: store size vs per-version copies',
        'parent links are WEAK for the sweep: retention is per-snapshot'],
  try:'click a ⋄ tile and walk the history through root and parent links — the DAG is browsable as pure data.',
  deep:'real deployments point commits at a ROOT-META tree fanning out to several index roots',
  run:()=>{}},
 {t:'durability: reconstruct, don\'t restore', f:'#storewrap',
  b:'The store write-behinds to IndexedDB, and the persisted meta is ONLY the mutable surface: refs + a reflog of commit hashes. Reload does not deserialize a snapshot — it RECONSTRUCTS the whole view by walking the Merkle objects. A swept commit comes back as a ghost: just its name, because its content died with its object.',
  look:['the header note: <b>persisted to IndexedDB</b>',
        'the <b>backup row</b>: Export pack = refs + reflog + every chunk in one file',
        'Import DEDUPS by name and RE-HASHES every object at the boundary — tampered bytes are refused',
        '<b>Reset storage</b>: the arm-then-confirm escape hatch'],
  try:'after the tour: reload the page and read the reconstruction narration; export a pack, Reset storage, import it back.',
  deep:'boot = reopen the database: read refs, walk objects, derive everything (entries from trees, written sets as reach-diffs)',
  run:()=>{}},
 {t:'the integrity lab', f:'#storewrap',
  b:'We just corrupted a stored chunk — its bytes changed, its NAME did not — and Verify store caught it cold, because every name is its own checksum. No external truth needed: re-hash the preimage, compare to the name.',
  look:['the <b style="color:var(--orph)">flagged rust tile</b> in the store',
        'the ribbon already shows the wrong value — the store is lying',
        'the verification tally in the narration'],
  try:'read a key from the corrupted chunk: the descent HALTS at the mismatch (ProllyCorruptionException). The lie even persists across reload — and stays detectable.',
  deep:'verify-below-the-cache (ADR-0064); pack import runs the same re-hash at the boundary',
  run:()=>{ el('corruptbtn').click(); el('verifybtn').click(); }},
 {t:'proofs, not promises', f:'#chartcard',
  b:'The finale is two computed facts. Convergence: an independent replica inserted the same keys in a random order and reached a byte-identical root — history-independence, which diff, merge, and sync all rest on. And the chart: written-per-op hugs the dashed height line while the grey tree-size line climbs.',
  look:['the narration: <b>convergence ✓ … byte-identical</b>',
        'flat bars vs the climbing tree-size line',
        'one <b style="color:var(--orph)">flagged lie</b> still sits in the store from the last stop'],
  try:'homework: heal the corruption — Rebuild, Reset storage, or export-a-healthy-pack-and-reimport all work. Everything stays live; the tour is over.',
  deep:'the engine pins these with differential + property tests; this page pins them in write-path-explorer-core.spec.ts',
  run:()=>el('converge').click()},
 {t:'the real engine', f:'#modeswitch',
  b:'Everything so far was the SIM — a toy-scale model you can see into byte by byte. The data-mode switch makes the ACTUAL Java engine the store: <b>sim + shadow</b> mirrors your key set to it and compares write sets (the sim splits at ~35 B; the engine\'s 512 B–16 KiB chunks hold hundreds of keys), and <b>real engine</b> renders and edits the engine\'s own tree over HTTP — real root hashes, measured write sets, reads with their measured descent, and a root-log that time-travels through superseded roots.',
  look:['the store switch in the HEADER — page-global state lives with the theme + tour controls',
        'switching NEVER migrates data: each mode shows its own store',
        'sim-only instruments dim in real mode — the backend deliberately has no commits, branches, or byte lens'],
  try:'run <code>mvn -pl prolly-playground-service spring-boot:run</code> (or open this page at localhost:8080, already served by it), then flip to real engine.',
  deep:'the backend runs the actual MutableMap → TreeMutator write path and Cursor reads; every panel number is measured from the store, never re-derived'},
];

let tourPos=-1;
function tourFocus(sel){
  document.querySelectorAll('.tourfocus').forEach(e2=>e2.classList.remove('tourfocus'));
  if(sel){ const t=document.querySelector(sel); if(t){ t.classList.add('tourfocus'); t.scrollIntoView({block:'nearest',behavior:reduced?'auto':'smooth'}); } }
}
function tourShow(){
  const st=TOUR[tourPos];
  el('tourtitle').textContent=`${tourPos+1}/${TOUR.length} · ${st.t}`;
  el('tourprog').style.width=(100*(tourPos+1)/TOUR.length)+'%';
  el('tourbody').innerHTML=
    `<p class="tlead">${st.b}</p>`+
    (st.look? `<div class="tlabel">watch for</div><ul>${st.look.map(x=>`<li>${x}</li>`).join('')}</ul>`:'')+
    (st.try? `<div class="tlabel">try it</div><p class="ttry">${st.try}</p>`:'')+
    (st.deep? `<div class="tlabel">in the engine</div><p class="tdeep">${st.deep}</p>`:'');
  el('tourbody').scrollTop=0;
  el('tourback').disabled=tourPos===0;
  el('tournext').textContent= tourPos===TOUR.length-1? 'finish' : 'next →';
  tourFocus(st.f);
}
function tourStep(dir){
  // the tour IS a sim walkthrough — driving it from REAL mode would mutate the
  // sim behind the user's back; make the mode change explicit instead (G-1)
  if(typeof dataMode!=='undefined' && dataMode!=='sim') setDataMode('sim');
  if(dir>0){
    tourPos++;
    if(tourPos>=TOUR.length){ tourEnd(); return; }
    try{ if(TOUR[tourPos].run) TOUR[tourPos].run(); }catch(e2){ console.error('tour step failed',e2); }
  } else if(tourPos>0){ tourPos--; } // back rewinds the TEXT only — state stays
  tourShow();
}
function tourEnd(){
  tourPos=-1; el('tourcard').hidden=true; tourFocus(null);
}
/* ---------- theme toggle: every color flows through the tokens ---------- */
const THEME_KEY='wp-theme';
function applyTheme(t){
  document.documentElement.dataset.theme=t;
  el('themebtn').textContent = t==='light'? 'dark mode' : 'light mode';
}
let theme='dark';
try{
  theme=localStorage.getItem(THEME_KEY)
    || (matchMedia('(prefers-color-scheme: light)').matches? 'light':'dark');
}catch(e){}
applyTheme(theme);
el('themebtn').onclick=()=>{
  theme = theme==='light'? 'dark':'light';
  try{ localStorage.setItem(THEME_KEY, theme); }catch(e){}
  applyTheme(theme);
};

el('tourbtn').onclick=()=>{ tourPos=-1; el('tourcard').hidden=false; tourStep(1); };
el('tournext').onclick=()=>tourStep(1);
el('tourback').onclick=()=>tourStep(-1);
el('tourend').onclick=tourEnd;

(async()=>{
  const restored=await persistBoot();
  if(!restored) freshBuild();
  if(formatWipeMsg){ el('simnarr').innerHTML=formatWipeMsg; formatWipeMsg=null; }
})();
