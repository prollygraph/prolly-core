/*
 * write-path-explorer.state.js — the mutable world: the commits view, refs
 * (branches/tags), selection, commitOp (mint + pointer swap), and the
 * single-key ops. Depends on core.js.
 */
/* ---------- commits ---------- */
let commits=[];   // {id,label,rootHash,parent,entries,written:Set,total,height,touchedKey}
let selected=-1;  // index into commits
// The manifest: the ONLY mutable state in the whole model. A branch is a
// name → commit pointer; creating/deleting/advancing one never touches a chunk.
let branches=new Map();          // name -> commitId (the tip) — MUTABLE refs
let tags=new Map();              // name -> tag-object hash — IMMUTABLE pins
let currentBranch=null;          // the checked-out ref; null = detached
let lensKeyX=new Map();          // key -> x offset of its first byte in the lens (locate)
let lensLevel=0;                 // which level's grouping the lens replays (0 = leaves)
let readPath=null;               // {commit, set:Set<hash>} — the last lookup's descent
let diffBase=null;               // commit id pinned as diff base (shift-click a DAG node)
let readAnim=null;               // {steps,pos,res,k,commit,timer,group} — the stepper state
let traceLog=[];                 // persistent journal of trace groups — survives ops; 'clear' empties it
let afterNodePos=new Map();      // hash -> {x,y} in the AFTER pane (cursor-dot coordinates)
let selectedKey=null, pickedChunk=null;

/** Mint the COMMIT OBJECT as a chunk in the same store: its content is the tree
    root + the parent commit hashes + the message, and its name is the hash of
    that content — so the history is a Merkle DAG (any rewrite of an ancestor
    would change every descendant's hash). The sweep treats parent links as
    WEAK references: retention here is per-snapshot (shallow), so ancestors can
    still be orphaned — a deliberate, stated policy. */
function mintCommitChunk(rootHash, parents, message){
  const ps=parents.filter(x=>x);
  // the message is part of the hashed CONTENT — it lives in the object, nowhere
  // else; the preimage is unchanged from earlier versions, so hashes are stable
  const hash=hid(`C|root:${rootHash||'-'}|p:${ps.join(',')||'-'}|${message}`);
  if(!POOL.has(hash)) POOL.set(hash,{hash, commit:true, root:rootHash||null, parents:ps, message});
  return hash;
}

/** A TAG is the fourth object kind (blob/tree/commit/tag): an immutable, named,
    content-addressed pin. Unlike a branch (a mutable name→commit entry in the
    manifest), a tag can never move — only be deleted — and it retains its
    commit through EVERY sweep policy. */
function mintTag(name, chash){
  const hash=hid(`T|${name}|${chash}`);
  if(!POOL.has(hash)) POOL.set(hash,{hash, tag:true, name, commit0:chash});
  return hash;
}

/** Serialized size of one object — its preimage length (the honest byte form). */
function streamBytes(h){
  const n=POOL.get(h); if(!n) return 0;
  const pre=preimageOf(n);
  return pre? pre.length : 0;
}
function fmtBytes(b){
  return b>=1048576? (b/1048576).toFixed(1)+' MB' : b>=1024? (b/1024).toFixed(1)+' KB' : b+' B';
}

/** The sweep's keep-set under the CURRENT retention policy — used by BOTH the
    GC button and the sweepable gauge, so the gauge predicts the button exactly. */
function computeKeepSet(){
  const keepIdx=new Set([selected]);
  const mode=el('gckeep').value;
  if(mode==='last3') commits.slice(-3).forEach(c=>keepIdx.add(c.id));
  if(mode==='tips') branches.forEach(id=>keepIdx.add(id));
  tags.forEach(th=>{
    const t=POOL.get(th); if(!t) return;
    const cm=commits.find(c2=>c2.chash===t.commit0);
    if(cm) keepIdx.add(cm.id);
  });
  const keep=new Set();
  tags.forEach(th=>keep.add(th));
  keepIdx.forEach(i=>{ for(const h of reach(commits[i].rootHash)) keep.add(h);
    if(commits[i].chash) keep.add(commits[i].chash); });
  return {keepIdx, keep};
}

function treeHeight(rootHash){ let h=POOL.get(rootHash); return h? h.level+1 : 0; }
function commitOp(label, entries, parentIdx, touchedKey, opts={}){
  // The chunking config is FORMAT-DEFINING: every op inherits the history's
  // config, pinned at genesis — the controls only apply on Rebuild. Otherwise a
  // mid-history control change would (a) rewrite the whole tree on the next op
  // and (b) make the boundary lens replay old commits with the WRONG splitter
  // parameters, silently disagreeing with the trees it explains.
  const cfg = parentIdx>=0 ? commits[parentIdx].cfg
                           : {avg:+el('avg').value, seed:+el('seed').value};
  const rootHash = buildTree(entries, cfg.avg, cfg.seed);
  const r = reach(rootHash);
  naiveBytes += r.size;
  const pr = parentIdx>=0 ? reach(commits[parentIdx].rootHash) : new Set();
  const written = new Set([...r].filter(h=>!pr.has(h)));
  const c = {id:commits.length, label, rootHash, parent:parentIdx, entries:entries.map(e=>({...e})),
             written, total:r.size, height:treeHeight(rootHash), touchedKey, pruned:false, cfg,
             touchedKeys:opts.touchedKeys||null, parent2:opts.parent2??null};
  for(const h of written){ const n=POOL.get(h); if(n && (n.bornAt==null || n.bornAt===-1)) n.bornAt=c.id; }
  const pHash=parentIdx>=0? commits[parentIdx].chash : null;
  const p2Hash=(opts.parent2!=null && opts.parent2>=0)? commits[opts.parent2].chash : null;
  c.chash=mintCommitChunk(rootHash,[pHash,p2Hash],label);
  const cchunk=POOL.get(c.chash); if(cchunk.bornAt==null) cchunk.bornAt=c.id;
  commits.push(c);
  c.ref=c.ref??null;
  // the pointer swap: if the checked-out branch's tip is the base of this write,
  // the ref advances; writing from anywhere else leaves you detached (the commit
  // exists, referenced by nothing — name it with a branch to keep it findable).
  if(currentBranch!=null && branches.get(currentBranch)===parentIdx){
    branches.set(currentBranch, c.id);
    c.ref=currentBranch;
  } else if(parentIdx>=0){
    currentBranch=null;
    c.ref=null;
  }
  selected=c.id; selectedKey=null; pickedChunk=null;
  diffBase=null;
  persistCommitChunks(c);
  renderAll(); narrate(c, pr);
  if(parentIdx>=0 && animWrites) startWriteTrace(c);
  return c;
}
let animWrites=true; // stress suppresses the per-op write trace

/* ---------- ops ---------- */
function el(id){ return document.getElementById(id); }
function cur(){ return commits[selected]; }
/** Display form of a commit's identity — the CONTENT HASH, not its sequence
    position (the array index stays as internal plumbing only). */
function cshort(id){
  const c=commits[id];
  return c && c.chash? '⋄'+c.chash.slice(0,8) : '#'+id;
}
function baseEntries(){ return cur().entries.map(e=>({...e})); }

function doInsert(k){
  const es=baseEntries();
  if(es.some(e=>e.k===k)){ hint(`key ${k} already exists — pick another`); return; }
  es.push({k,v:1}); es.sort((a,b)=>a.k-b.k);
  commitOp(`insert ${k}`, es, selected, k);
}
function doUpdate(k){
  const es=baseEntries(); const e=es.find(e=>e.k===k); if(!e) return;
  e.v++; commitOp(`update ${k}`, es, selected, k);
}
function doDelete(k){
  const es=baseEntries().filter(e=>e.k!==k);
  commitOp(`delete ${k}`, es, selected, k);
}
function randomOp(rng){
  const es=cur().entries;
  if(!es.length){ doInsert(50); return; }               // empty tree: the only sensible op
  const roll=rng();
  if(roll<0.5 || es.length<2){ // insert into a random gap
    for(let t=0;t<20 && es.length>1;t++){
      const i=Math.floor(rng()*(es.length-1));
      const a=es[i].k, b=es[i+1].k;
      if(b-a>1){ doInsert(a+1+Math.floor(rng()*(b-a-1))); return; }
    }
    doInsert(es[es.length-1].k+1+Math.floor(rng()*9));
  } else if(roll<0.8){ doUpdate(es[Math.floor(rng()*es.length)].k); }
  else if(es.length>4){ doDelete(es[Math.floor(rng()*es.length)].k); }
  else { doUpdate(es[Math.floor(rng()*es.length)].k); }
}
function hint(msg){ el('writehint').textContent=msg; }

