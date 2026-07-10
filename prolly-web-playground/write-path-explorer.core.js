/*
 * write-path-explorer.core.js — the PURE simulation: hashing, the pool, the
 * rolling-hash splitter, tree build + the fast-forward walk, reachability,
 * preimage/verification, read/scan traces, the batch grammar.
 * KEEP THIS FILE DOM-FREE AND d3-FREE: write-path-explorer-core.spec.ts
 * evaluates it verbatim node-side as the invariant net.
 * Load order: core → state → render → controls → app (classic scripts,
 * shared globals; type=module is blocked on file://).
 */
'use strict';
/* ---------- hashing + rng ---------- */
function fnv(s, seed){ let h=(seed??0x811c9dc5)>>>0; for(let i=0;i<s.length;i++){ h^=s.charCodeAt(i); h=Math.imul(h,0x01000193)>>>0; } return h>>>0; }
function hid(s){ return (fnv(s).toString(16).padStart(8,'0') + fnv(s+'',0x1000193).toString(16).padStart(8,'0')).slice(0,12); }
function mulberry(a){ return function(){ a|=0; a=a+0x6D2B79F5|0; let t=Math.imul(a^a>>>15,1|a); t=t+Math.imul(t^t>>>7,61|t)^t; return ((t^t>>>14)>>>0)/4294967296; }; }

/* ---------- the content-addressed pool (never shrinks; grows by the amber set) ---------- */
const POOL = new Map();          // hash -> node
let naiveBytes = 0;              // Σ per-commit reachable counts, i.e. cost if each version were stored whole

/* ---------- tree build: content-defined chunking via a REAL rolling hash ----------
   A BuzHash rolls over the serialized entry bytes (toy scale: 8-byte window vs the
   engine's 67; ~10-byte minimum vs 512 B; per-chunk reset, like the engine).
   Boundaries latch mid-entry and close at entry granularity — exactly
   RollingHashSplitter.append(key,value) + crossedBoundary(). */
const WIN=8;
const rotl=(x,r)=>((x<<r)|(x>>>(32-r)))>>>0;
const buzTables=new Map();
function buzTable(seed){
  if(!buzTables.has(seed)){
    const rng=mulberry(fnv('buz|'+seed));
    buzTables.set(seed, Array.from({length:256},()=>Math.floor(rng()*4294967296)>>>0));
  }
  return buzTables.get(seed);
}
class Splitter{
  constructor(avg,table){
    this.T=Math.max(4, avg*7-10);      // trigger rarity: E[chunk] ≈ MINB + T bytes ≈ avg entries
    this.MINB=10; this.MAXB=avg*7*3;   // scaled analogs of the 512 B ramp + 16 KiB cap
    this.table=table; this.reset();
  }
  reset(){ this.h=0; this.win=[]; this.since=0; this.crossed=false; }
  /** roll one byte; returns a state tag the boundary lens renders */
  byte(b){
    this.h=(rotl(this.h,1)^this.table[b&255])>>>0;
    this.win.push(b&255);
    if(this.win.length>WIN){ const out=this.win.shift(); this.h=(this.h^rotl(this.table[out],WIN%32))>>>0; }
    this.since++;
    if(this.crossed) return 'latched';
    if(this.since<this.MINB) return 'min';
    if(this.since>=this.MAXB){ this.crossed=true; return 'forced'; }
    if(this.h%this.T===0){ this.crossed=true; return 'fired'; }
    return 'roll';
  }
  appendStr(str){ for(let i=0;i<str.length;i++) this.byte(str.charCodeAt(i)); return this.crossed; }
}
const entryBytes=e=>`${e.k}=${e.v};`;

function mkLeafNode(c){
  const hash = hid('N0|'+c.map(e=>e.k+'='+e.v).join(','));
  if(!POOL.has(hash)) POOL.set(hash,{hash,level:0,count:c.length,entries:c.map(e=>({k:e.k,v:e.v})),
    children:null,span:[c[0].k,c[c.length-1].k]});
  return POOL.get(hash);
}

/** Rebuild the internal levels above a leaf run — deterministic from the leaves,
    so two paths that produce the same leaf run produce the same root. */
function buildUpper(nodes, avg, seed){
  const table=buzTable(seed);
  let level=0;
  while(nodes.length>1){
    level++;
    // same splitter, rolled over the (maxKey:childHash) item bytes; an internal
    // node never closes with a single child (the ADR-0069 degenerate guard) —
    // the latch holds the boundary until a 2nd child arrives.
    const spi=new Splitter(avg,table);
    const groups=[]; let g=[];
    for(const n of nodes){
      g.push(n);
      const crossed=spi.appendStr(`${n.span[1]}:${n.hash};`);
      if(crossed && g.length>=2){ groups.push(g); g=[]; spi.reset(); }
    }
    if(g.length) groups.push(g);
    nodes=groups.map(g2=>{
      // the per-child subtree counts are IN the preimage — the engine serializes
      // them as a varint vector inside the node bytes, so they are part of the
      // name; a tampered count is caught by the same re-hash as tampered data
      const hash = hid(`N${level}|`+g2.map(n=>`${n.span[1]}:${n.hash}@${n.count}`).join(','));
      if(!POOL.has(hash)) POOL.set(hash,{hash,level,count:g2.reduce((s2,n)=>s2+n.count,0),
        entries:null,children:g2.map(n=>n.hash),childKeys:g2.map(n=>n.span[1]),
        counts:g2.map(n=>n.count),
        span:[g2[0].span[0],g2[g2.length-1].span[1]]});
      return POOL.get(hash);
    });
  }
  return nodes[0].hash;
}

function buildTree(entries, avg, seed){
  if(!entries.length) return null;
  const sp=new Splitter(avg,buzTable(seed));
  const chunks=[]; let cur=[];
  for(const e of entries){
    cur.push(e);
    if(sp.appendStr(entryBytes(e))){ chunks.push(cur); cur=[]; sp.reset(); }
  }
  if(cur.length) chunks.push(cur);
  return buildUpper(chunks.map(mkLeafNode), avg, seed);
}

/** THE FAST-FORWARD WALK, simulated at the leaf level (TreeMutator's
    synchronize-then-skip, ADR-0068): merge a SORTED edit stream into the base
    tree's leaf run. A base leaf is SKIPPED BY REFERENCE — never opened — when
    the splitter sits at a fresh chunk start (boundary aligned) and no edit is
    due inside it; otherwise the leaf is opened, its entries merged with the due
    edits, and re-chunked through the splitter until the run resyncs.
    Differential-pinned: the resulting leaf run (and therefore the root, since
    the upper levels are deterministic from the leaves) must equal the
    from-scratch build — enforced by the core net. Edits: {k,v} puts (insert or
    update), {k,v:null} deletes. */
function ffApply(baseRoot, edits, avg, seed){
  const steps=[]; const outLeaves=[];
  const base= baseRoot? lensLevelNodes(baseRoot,0) : [];
  const sp=new Splitter(avg, buzTable(seed));
  let cur=[]; let ei=0;
  const closeChunk=()=>{
    const n=mkLeafNode(cur);
    outLeaves.push(n);
    steps.push({t:'emit', node:n});
    cur=[]; sp.reset();
  };
  const emitEntry=(e)=>{
    cur.push({k:e.k, v:e.v});
    if(sp.appendStr(entryBytes(e))) closeChunk();
  };
  for(const leaf of base){
    const editDue = ei<edits.length && edits[ei].k<=leaf.span[1];
    if(!editDue && cur.length===0){
      // boundary aligned + untouched: the whole leaf rides along by reference
      outLeaves.push(leaf);
      steps.push({t:'skip', node:leaf});
      continue;
    }
    steps.push({t:'open', node:leaf});
    for(const e of leaf.entries){
      while(ei<edits.length && edits[ei].k<e.k){
        const ed=edits[ei++]; if(ed.v!=null) emitEntry(ed);
      }
      if(ei<edits.length && edits[ei].k===e.k){
        const ed=edits[ei++]; if(ed.v!=null) emitEntry(ed); // update replaces, delete drops
      } else emitEntry(e);
    }
    if(cur.length===0) steps.push({t:'resync', k:leaf.span[1]});
  }
  while(ei<edits.length){ const ed=edits[ei++]; if(ed.v!=null) emitEntry(ed); }
  if(cur.length) closeChunk();
  return {leaves:outLeaves, steps, root: outLeaves.length? buildUpper(outLeaves.slice(), avg, seed) : null};
}

/** Pure splitter replay for the boundary lens: per-byte states + the keys after
    which a chunk closed. One replay serves EVERY level — the same mechanism
    chunks entries into leaves and (maxKey:childHash) items into internal nodes.
    Must agree with buildTree by construction (same Splitter, same bytes, same
    min-2-children guard at internal levels). */
function lensReplay(items, avg, seed, level){
  const sp=new Splitter(avg,buzTable(seed));
  const cells=[]; const boundKeys=[]; let chunkIdx=0, inChunk=0;
  for(const it of items){
    inChunk++;
    for(let i=0;i<it.str.length;i++){
      const tag=sp.byte(it.str.charCodeAt(i));
      cells.push({ch:it.str[i],tag,mod:sp.h%sp.T,T:sp.T,k:it.k,first:i===0,since:sp.since,chunk:chunkIdx});
    }
    const degenerateGuardOk = level===0 || inChunk>=2; // ADR-0069: internal nodes need >=2 children
    if(sp.crossed && degenerateGuardOk){ boundKeys.push(it.k); sp.reset(); chunkIdx++; inChunk=0; }
  }
  return {cells,boundKeys,T:Math.max(4,avg*7-10)};
}
/** The item stream feeding level {@code level}'s grouping: entries for level 0,
    (maxKey:childHash); items of level-1 nodes for level k. */
function lensStream(rootHash, entries, level){
  if(level===0) return entries.map(e=>({str:entryBytes(e), k:e.k}));
  const nodes=lensLevelNodes(rootHash, level-1);
  return nodes.map(n=>({str:`${n.span[1]}:${n.hash};`, k:n.span[1]}));
}
/** All nodes at one level of a tree, in key order. */
function lensLevelNodes(rootHash, level){
  const out=[];
  (function walk(h){ const n=POOL.get(h); if(!n) return;
    if(n.level===level){ out.push(n); return; }
    if(n.children) n.children.forEach(walk); })(rootHash);
  return out;
}
/** Back-compat leaf-level wrapper (the core spec + parent-ghost path use it). */
function lensData(entries, avg, seed){
  return lensReplay(entries.map(e=>({str:entryBytes(e), k:e.k})), avg, seed, 0);
}

/** RANGE SCAN — the prefix scan's true form (over ordered keys a prefix IS a
    [lo,hi] range): descend to the first leaf covering `from` (the same seek
    rule as a point read), then walk leaves left-to-right — hopping via the
    parent stack (ascend, advance one child, descend; only NEW nodes count as
    fetches) — until the STOP PREDICATE fires: the first key past `to` ends the
    walk without ever knowing the last key in advance. Leaves right of the stop
    are never touched. Every fetch re-hashes (verify-below-the-cache). */
function scanTrace(rootHash, from, to){
  const steps=[], results=[], fetched=new Set();
  const out={steps, results, fetched, corrupt:null, stopped:false};
  if(!rootHash){ steps.push({type:'sdone', txt:'empty tree — nothing to scan, zero fetches'}); return out; }
  const fetch=(h,why)=>{
    const n=POOL.get(h); if(!n) return null;
    const vr=verifyChunk(h);
    if(vr.ok===false){
      steps.push({type:'stop', node:n,
        txt:`re-hash: bytes hash to ${vr.actual.slice(0,10)}, the NAME says ${h.slice(0,10)} → CORRUPTION DETECTED — the scan halts`});
      out.corrupt={at:h}; return null;
    }
    fetched.add(h);
    steps.push({type:'fetch', node:n,
      txt:`fetch ${h.slice(0,10)} — ${n.level===0?'leaf':'L'+n.level}${why? ' · '+why:''} · re-hashed ✓ (${fetched.size} fetches)`});
    return n;
  };
  const stack=[]; let h=rootHash;
  for(;;){
    const n=fetch(h, stack.length? 'descend':'the root');
    if(!n) return out;
    if(!n.children) break;
    let idx=-1;
    for(let i=0;i<n.children.length;i++){
      const kid=POOL.get(n.children[i]);
      if(kid && kid.span[1]>=from){ idx=i; break; }
      steps.push({type:'cmp', node:n, childHash:n.children[i],
        txt:`child ${i+1} maxKey ${kid? kid.span[1]:'?'} < from=${from} → skip its whole subtree`});
    }
    if(idx<0){
      steps.push({type:'sdone', node:n, txt:`from=${from} exceeds every maxKey — empty range, answered in ${fetched.size} fetch${fetched.size===1?'':'es'}`});
      return out;
    }
    steps.push({type:'cmp', node:n, childHash:n.children[idx],
      txt:`child ${idx+1} maxKey ≥ from=${from} → descend ↓`});
    stack.push({n, idx});
    h=n.children[idx];
  }
  leafLoop: for(;;){
    const leaf=POOL.get(h);
    let shown=0;
    for(const e of leaf.entries){
      if(e.k<from) continue;
      if(e.k>to){
        steps.push({type:'stop', node:leaf, ek:e.k,
          txt:`key ${e.k} > to=${to} → STOP — the stop predicate ends the walk; no leaf to the right is ever touched`});
        out.stopped=true;
        break leafLoop;
      }
      results.push({k:e.k, v:e.v});
      shown++;
      if(shown<=6){
        steps.push({type:'skey', node:leaf, ek:e.k, txt:`  ${e.k} = v${e.v} ✓ in range (${results.length} so far)`});
      } else if(shown===7){
        steps.push({type:'skey', node:leaf, txt:`  … this leaf keeps matching (aggregated)`});
      }
    }
    let hop=null;
    while(stack.length){
      const top=stack[stack.length-1];
      if(top.idx+1 < top.n.children.length){ top.idx++; hop=top; break; }
      stack.pop();
    }
    if(!hop){
      steps.push({type:'sdone', txt:`end of tree reached — the range ran off the right edge`});
      break;
    }
    steps.push({type:'cmp', node:hop.n, childHash:hop.n.children[hop.idx],
      txt:`leaf exhausted → hop right: ascend to L${hop.n.level}, advance to child ${hop.idx+1}, descend`});
    h=hop.n.children[hop.idx];
    let n2=fetch(h,'the next leaf');
    if(!n2) return out;
    while(n2 && n2.children){
      stack.push({n:n2, idx:0});
      h=n2.children[0];
      n2=fetch(h,'leftmost descent');
      if(!n2) return out;
    }
  }
  return out;
}

/** Parse the batch textarea: one op per line — `k` (put; insert, or bump v+1 if
    present), `k=v` (put with an explicit value), `-k` (delete), `#` comments and
    blank lines ignored. Validated SEQUENTIALLY against the base (read-your-own-
    writes): deleting a key inserted two lines up is legal; deleting an absent
    key is a per-line error. An invalid line blocks the WHOLE batch — atomicity
    is the contract, not a courtesy. */
function parseBatch(text, baseEntries){
  const keys=new Map(baseEntries.map(e=>[e.k,e.v]));
  const ops=[]; const errors=[]; let ins=0,upd=0,del=0;
  const seen=new Set(); const dups=new Set();
  text.split('\n').forEach((raw,idx)=>{
    const line=raw.replace(/#.*$/,'').trim();
    if(!line) return;
    const ln=idx+1; let m;
    if((m=line.match(/^-\s*(\d+)$/))){
      const k=+m[1];
      if(!keys.has(k)){ errors.push(`line ${ln}: cannot delete ${k} — not present at that point in the batch`); return; }
      keys.delete(k); ops.push({op:'del',k}); del++;
      if(seen.has(k)) dups.add(k); seen.add(k);
    } else if((m=line.match(/^(\d+)\s*(?:=\s*(\d+))?$/))){
      const k=+m[1];
      if(k>99999999){ errors.push(`line ${ln}: key ${k} is out of range`); return; }
      const exists=keys.has(k);
      const v= m[2]!=null? +m[2] : (exists? keys.get(k)+1 : 1);
      keys.set(k,v); ops.push({op:'put',k,v});
      if(exists) upd++; else ins++;
      if(seen.has(k)) dups.add(k); seen.add(k);
    } else {
      errors.push(`line ${ln}: cannot parse '${raw.trim()}' — use k, k=v, -k, or a # comment`);
    }
  });
  return {ops, errors, ins, upd, del, dups:[...dups]};
}

/** The canonical byte-form every object was hashed from — reconstructable from
    the object ALONE (internal nodes carry their child keys, like the engine's).
    Verification = re-hash the preimage and compare to the NAME: a
    content-addressed store needs no external truth, every name is its own
    checksum (verify-below-the-cache, ADR-0064). */
function preimageOf(n){
  if(n.commit) return `C|root:${n.root||'-'}|p:${n.parents.join(',')||'-'}|${n.message??n.label}`;
  if(n.tag) return `T|${n.name}|${n.commit0}`;
  if(!n.children) return 'N0|'+n.entries.map(e=>e.k+'='+e.v).join(',');
  const keys=n.childKeys ?? n.children.map(ch=>{ const kid=POOL.get(ch); return kid&&kid.span? kid.span[1]:null; });
  if(keys.some(k=>k==null)) return null;
  if(!n.counts || n.counts.length!==n.children.length) return null; // counts are part of the bytes — an internal node without them is not this format
  return `N${n.level}|`+n.children.map((ch,i)=>keys[i]+':'+ch+'@'+n.counts[i]).join(',');
}
/** What-if replay (the boundary lens's perturb mode): the SAME item stream with
    exactly ONE byte changed (to the next printable char — deterministic), re-run
    through the same splitter. Content-defined chunking's core locality claim
    becomes checkable: hashing is causal, so boundaries strictly BEFORE the
    mutated byte cannot move, and the stream RESYNCS at the first surviving
    boundary after it — one byte never rewrites distant chunks. */
function lensCounterfactual(items, avg, seed, level, byteIdx){
  let off=0, mut=null;
  const items2=items.map(it=>{
    const s=it.str;
    if(mut==null && byteIdx<off+s.length){
      const j=byteIdx-off;
      const code=s.charCodeAt(j);
      const nc=String.fromCharCode(code>=126?33:code+1);
      mut={from:s[j], to:nc, key:it.k};
      off+=s.length;
      return {...it, str:s.slice(0,j)+nc+s.slice(j+1)};
    }
    off+=s.length;
    return it;
  });
  if(!mut) return null;
  const rep=lensReplay(items2, avg, seed, level);
  return {mut, boundKeys:rep.boundKeys};
}

/** The counted-B-tree accessor contract, mirrored from the engine: the BYTES hold
    per-child counts (a varint vector in the real node), but every consumer reads
    CUMULATIVE prefix sums — Node.getSubtreeCount(i) = entries under children 0..i.
    Rank arithmetic ("entries before child i" = prefix[i-1]) needs the cumulative
    form; a per-child accessor silently corrupts every ordinal consumer (the
    SubtreeCountContractProperty lesson). */
function prefixCounts(n){
  if(!n.counts) return null;
  const out=[]; let s=0;
  for(const c of n.counts){ s+=c; out.push(s); }
  return out;
}

function verifyChunk(h){
  const n=POOL.get(h); if(!n) return {missing:true};
  const pre=preimageOf(n); if(pre==null) return {skip:true};
  const actual=hid(pre);
  return {ok:actual===h, actual};
}

/** The READ path — a point lookup's descent, exactly the cursor's seek rule: at
    each internal node take the FIRST child whose maxKey >= k; if none, the key
    exceeds the whole subtree and the search stops early. O(height) node fetches;
    every fetch is a content-addressed read, so the root hash alone pins the
    entire snapshot. */
function readTrace(rootHash, k){
  const steps=[]; let h=rootHash;
  while(h){
    const n=POOL.get(h);
    if(!n){ return {steps, found:false, swept:true}; }
    const vr=verifyChunk(h);
    if(vr.ok===false){
      steps.push({node:n, corrupt:true, actual:vr.actual});
      return {steps, found:false, corrupt:{at:h, actual:vr.actual}};
    }
    if(n.level===0){
      const entry=n.entries.find(e=>e.k===k)||null;
      steps.push({node:n, leaf:true, entry});
      return {steps, found:!!entry, entry};
    }
    let chosen=-1, childMax=null;
    for(let i=0;i<n.children.length;i++){
      const kid=POOL.get(n.children[i]);
      if(kid && kid.span[1]>=k){ chosen=i; childMax=kid.span[1]; break; }
    }
    steps.push({node:n, leaf:false, chosen, childMax, fan:n.children.length});
    if(chosen<0) return {steps, found:false, early:true};
    h=n.children[chosen];
  }
  return {steps, found:false};
}
function reach(rootHash){
  const seen=new Set(); if(rootHash==null) return seen;
  const stack=[rootHash];
  while(stack.length){ const h=stack.pop(); if(seen.has(h))continue; seen.add(h);
    const n=POOL.get(h); if(n && n.children) for(const c of n.children) stack.push(c); }
  return seen;
}

