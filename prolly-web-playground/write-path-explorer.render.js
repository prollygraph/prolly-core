/*
 * write-path-explorer.render.js — every panel's renderer (d3 + DOM): panes,
 * commit graph, ribbon, lens, store, stats, inspector, blame, the trace
 * stepper machinery, and renderAll. Depends on core+state.
 */
/* ---------- layout + render (d3) ---------- */
const NW=66, NH=34, XGAP=10, YGAP=58;
function layoutTree(rootHash, pool=POOL){
  if(rootHash==null) return {nodes:[],links:[],w:0,h:0};
  const nodes=[],links=[]; let leafX=0, maxLevel=0;
  (function walk(h,parent){
    const n=pool.get(h); if(!n) return {x:0,y:0,hash:h,node:{level:0,span:[0,0],count:0},parent};
    maxLevel=Math.max(maxLevel,n.level);
    const d={hash:h,node:n,x:0,y:0,parent};
    if(!n.children){ d.x=leafX; leafX+=NW+XGAP; }
    else{
      const kids=n.children.filter(c=>pool.get(c)).map(c=>walk(c,d));
      if(!kids.length){ d.x=leafX; leafX+=NW+XGAP; nodes.push(d); return d; }
      d.x=(kids[0].x+kids[kids.length-1].x)/2;
      for(const k of kids) links.push({s:d,t:k});
    }
    nodes.push(d); return d;
  })(rootHash,null);
  const H=(maxLevel+1)*YGAP;
  for(const d of nodes) d.y = H - d.node.level*YGAP;   // leaves at bottom
  return {nodes,links,w:leafX-XGAP,h:H+NH};
}
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const paneCtl = {}; // svgId -> {svg (d3 sel), zoom, fit (the fit-to-content transform)}

// pool selects the node source (the sim's POOL by default; REAL mode passes its
// fetched pool); pick overrides the node-click action (default: sim inspector).
function renderPane(svgId, commit, statusOf, headText, pool=POOL, pick=null){
  const svg=d3.select('#'+svgId); svg.selectAll('*').remove();
  const holder=svg.append('g');
  paneCtl[svgId]=null;
  if(!commit){ el(svgId==='beforesvg'?'beforeh':'afterh').textContent='— genesis: nothing before'; return; }
  if(commit.rootHash==null){
    el(svgId==='beforesvg'?'beforeh':'afterh').textContent=`${cshort(commit.id)} root ∅`;
    svg.append('text').attr('x','50%').attr('y',165).attr('text-anchor','middle')
      .attr('fill','var(--faint)').attr('font-size',13)
      .text('∅ empty tree — no chunks, no root; the manifest points at nothing');
    return;
  }
  if(!pool.has(commit.rootHash)){
    el(svgId==='beforesvg'?'beforeh':'afterh').textContent=`${cshort(commit.id)} pruned`;
    svg.append('text').attr('x','50%').attr('y',165).attr('text-anchor','middle')
      .attr('fill','var(--orph)').attr('font-size',13)
      .text('pruned — this commit\'s chunks were swept by garbage collection');
    return;
  }
  el(svgId==='beforesvg'?'beforeh':'afterh').textContent=headText;
  const L=layoutTree(commit.rootHash, pool);
  const g=holder.append('g');
  g.selectAll('path.lk').data(L.links).join('path')
    .attr('class',d=>{ const st=statusOf(d.t.hash);
      return 'lk'+(st==='new'?' new':st==='orph'?' orph':'')
        +((readPath && readPath.commit===commit.id && readPath.set.has(d.s.hash) && readPath.set.has(d.t.hash))?' onread':''); })
    .attr('d',d=>`M${d.s.x+NW/2},${d.s.y+NH}C${d.s.x+NW/2},${d.s.y+NH+YGAP/2} ${d.t.x+NW/2},${d.t.y-YGAP/2} ${d.t.x+NW/2},${d.t.y}`);
  if(svgId==='aftersvg') afterNodePos=new Map(L.nodes.map(d=>[d.hash,{x:d.x,y:d.y}]));
  const onRead=h=> readPath && readPath.commit===commit.id && readPath.set.has(h);
  const nd=g.selectAll('g.nd').data(L.nodes).join('g')
    .attr('class',d=>{ let c='nd '+statusOf(d.hash); if(pickedChunk===d.hash)c+=' picked';
      if(d.node.stub)c+=' stub'; if(onRead(d.hash))c+=' onread'; return c; })
    .attr('transform',d=>`translate(${d.x},${d.y})`)
    .attr('data-hash',d=>d.hash)
    .on('click',(ev,d)=>{ if(pick){ pick(d.hash); return; } pickedChunk=d.hash; inspect(d.hash); renderTrees(); })
    .on('mouseenter',(ev,d)=>{ if(!d.node.children && svgId==='aftersvg'){
        const cb=document.querySelector(`#ribbon .chunkbox[data-hash="${d.hash}"]`);
        if(cb) cb.classList.add('hoverlink'); } })
    .on('mouseleave',()=>{ document.querySelectorAll('#ribbon .chunkbox.hoverlink')
        .forEach(x=>x.classList.remove('hoverlink')); });
  nd.append('rect').attr('class','box').attr('width',NW).attr('height',NH);
  nd.append('text').attr('class','nk').attr('x',NW/2).attr('y',14).attr('text-anchor','middle')
    .text(d=>d.node.stubLabel ?? (d.node.level===0? `${d.node.span[0]}‥${d.node.span[1]}` : `L${d.node.level}·${d.node.count}`));
  nd.append('text').attr('class','nh').attr('x',NW/2).attr('y',27).attr('text-anchor','middle')
    .text(d=>d.hash.slice(0,6));
  // fit + zoom
  const bw=svg.node().clientWidth||600, bh=330;
  const s=Math.min(bw/(L.w+40), bh/(L.h+30), 1.1);
  const tx=(bw-L.w*s)/2, ty=(bh-L.h*s)/2+6;
  const zoom=d3.zoom().scaleExtent([0.15,3]).on('zoom',ev=>holder.attr('transform',ev.transform));
  const fit=d3.zoomIdentity.translate(tx,ty).scale(s);
  svg.call(zoom);
  svg.call(zoom.transform, fit);
  paneCtl[svgId]={svg, zoom, fit};
}

function renderTrees(){
  const c=cur();
  let p=c.parent>=0? commits[c.parent] : null, diffMode=false;
  if(diffBase!=null){
    const b=commits[diffBase];
    if(b && !b.pruned && diffBase!==c.id){ p=b; diffMode=true; } else diffBase=null;
  }
  const rNew=reach(c.rootHash);
  const rBase=diffMode? reach(p.rootHash) : null;
  renderPane('beforesvg', p,
    h=> rNew.has(h)? 'shared' : 'orph',
    p? (diffMode? `${cshort(p.id)} DIFF BASE — shift-click to unpin`
                : `${cshort(p.id)} root ${p.rootHash? p.rootHash.slice(0,8):'∅'}`) : '');
  renderPane('aftersvg', c,
    h=> diffMode? (rBase.has(h)? 'shared':'new') : (c.written.has(h)? 'new' : 'shared'),
    `${cshort(c.id)} root ${c.rootHash? c.rootHash.slice(0,8):'∅'}`+(diffMode? ` vs base ${cshort(p.id)}`:''));
  // a re-render (node click, resize) redraws the pane — restore the stepper's
  // visuals so inspecting a path chunk mid-scrub doesn't lose the trace
  if(readAnim && readAnim.commit===c.id && readAnim.pos>=0) applyStepsUpTo(readAnim.pos);
}

function renderRibbon(){
  const c=cur();
  const rBase=(diffBase!=null && commits[diffBase] && !commits[diffBase].pruned)
    ? reach(commits[diffBase].rootHash) : null;
  const box=el('ribbon'); box.innerHTML='';
  if(c.rootHash==null){
    const msg=document.createElement('span'); msg.className='hint';
    msg.textContent='empty tree — ';
    const b=document.createElement('button'); b.className='primary';
    b.textContent='Insert first key (50)'; b.onclick=()=>doInsert(50);
    el('ribbonmap').hidden=true;
    box.appendChild(msg); box.appendChild(b); syncButtons(); return;
  }
  // leaf chunks of AFTER, in order
  const leaves=[];
  (function walk(h){ const n=POOL.get(h); if(!n.children){leaves.push(n);return;} n.children.forEach(walk); })(c.rootHash);
  const mkGap=(a,b)=>{
    const g=document.createElement('button'); g.className='gap'; g.textContent='+';
    const ok=b-a>1; g.disabled=!ok;
    g.title=ok? `insert ${a+Math.floor((b-a)/2)}` : 'gap full';
    if(ok) g.onclick=()=>doInsert(a+Math.floor((b-a)/2));
    return g;
  };
  const baseVals=rBase? new Map(commits[diffBase].entries.map(e=>[e.k,e.v])) : null;
  let prevKey=null;
  leaves.forEach(n=>{
    const cb=document.createElement('div');
    cb.className='chunkbox'+((rBase? !rBase.has(n.hash) : c.written.has(n.hash))?' new':'');
    cb.dataset.hash=n.hash;
    // isolated splitter replay — exact, because the splitter resets per chunk
    const sp=new Splitter(c.cfg.avg, buzTable(c.cfg.seed));
    let fired=false, forced=false, bytes=0;
    for(const e of n.entries){
      const str=entryBytes(e); bytes+=str.length;
      for(let i=0;i<str.length;i++){
        const t=sp.byte(str.charCodeAt(i));
        if(t==='fired') fired=true; if(t==='forced') forced=true;
      }
    }
    const cause=fired?'trigger':forced?'cap':'tail';
    cb.title=`leaf ${n.hash} · ${n.count} keys · ${bytes} stream B · closed by ${cause}`;
    // hover ⇄ tree: light the corresponding leaf node in the AFTER pane
    cb.onmouseenter=()=>{ const nd=document.querySelector(`#aftersvg g.nd[data-hash="${n.hash}"]`); if(nd) nd.classList.add('hoverlink'); };
    cb.onmouseleave=()=>{ document.querySelectorAll('#aftersvg g.nd.hoverlink').forEach(x=>x.classList.remove('hoverlink')); };
    const tag=document.createElement('span'); tag.className='chash';
    tag.textContent=`${n.hash.slice(0,6)} ×${n.count}`;
    cb.appendChild(tag);
    // ribbon → lens: clicking the box FRAME (not its key/gap buttons) shows this
    // chunk's bytes below. NOT on the .chash tag — it is pointer-events:none by
    // design (it once intercepted clicks meant for neighbors; keep it inert).
    cb.style.cursor='pointer';
    cb.title=(cb.title? cb.title+' · ':'')+'click the box frame: show its bytes in the boundary lens';
    cb.onclick=ev=>{ if(ev.target!==cb) return; locateInLens(n.span[0]); };
    const bc=document.createElement('span'); bc.className='bcause bc-'+cause;
    bc.title= cause==='trigger'? 'boundary: the rolling-hash trigger fired here'
            : cause==='cap'? 'boundary: the max-size cap forced a close'
            : 'tail: the stream ended before any trigger — no real boundary';
    cb.appendChild(bc);
    let firstOfChunk=true;
    n.entries.forEach(e=>{
      if(prevKey!=null){
        const g=mkGap(prevKey,e.k);
        if(firstOfChunk){
          // the inter-CHUNK gap lives between the boxes — the splitter, not the
          // click position, decides which chunk the new key joins
          g.classList.add('gapb');
          g.title=(g.title||'')+' — lands between chunks; the splitter decides its side';
          box.appendChild(g);
        } else cb.appendChild(g);
      }
      firstOfChunk=false;
      const kc=document.createElement('button');
      const isT=c.touchedKey===e.k || (c.touchedKeys && c.touchedKeys.has(e.k));
      kc.className='keycell'+(selectedKey===e.k?' sel':'')+(isT?' touched':'');
      kc.dataset.k=e.k;
      kc.textContent=e.k+(e.v>1? '·v'+e.v:'');
      kc.title=`key ${e.k} = v${e.v} — click to select`;
      kc.onclick=()=>{ selectedKey= selectedKey===e.k? null : e.k; syncButtons(); renderRibbon();
        if(selectedKey!=null) renderKeyBlame(selectedKey); };
      if(baseVals){
        if(!baseVals.has(e.k)){ kc.classList.add('kadd'); kc.title+=' — ADDED vs the diff base'; }
        else if(baseVals.get(e.k)!==e.v){ kc.classList.add('kmod'); kc.title+=` — CHANGED vs base (v${baseVals.get(e.k)} → v${e.v})`; }
      }
      cb.appendChild(kc);
      prevKey=e.k;
    });
    box.appendChild(cb);
  });
  renderRibbonMap(leaves, n=> rBase? !rBase.has(n.hash) : c.written.has(n.hash));
  syncButtons();
}
function syncButtons(){
  el('update').disabled = selectedKey==null;
  el('delete').disabled = selectedKey==null;
  el('update').textContent = selectedKey==null? 'Update value' : `Update ${selectedKey}`;
  el('delete').textContent = selectedKey==null? 'Delete' : `Delete ${selectedKey}`;
  if(selectedKey!=null) hint(`key ${selectedKey} selected`);
}

function renderStrip(){
  const s=el('strip'); s.innerHTML='';
  if(!commits.length) return;
  // lane assignment, git-graph style: the FIRST child continues its parent's
  // lane; a later child branches into a new lane — REUSING a lane whose line
  // was merged away (its tip absorbed by a merge commit and never continued).
  const mergedBy={};
  commits.forEach(c=>{ if(c.parent2!=null && c.parent2>=0) mergedBy[c.parent2]=c.id; });
  const laneOf=[], tip=[], freeLanes=[];
  const alloc=(id)=>{
    if(freeLanes.length){ const L=freeLanes.shift(); tip[L]=id; return L; }
    tip.push(id); return tip.length-1;
  };
  commits.forEach(c=>{
    // free every lane whose current tip was merged strictly before this commit
    tip.forEach((t,L)=>{
      if(t!=null && mergedBy[t]!=null && mergedBy[t]<c.id && !freeLanes.includes(L)){
        tip[L]=null; freeLanes.push(L);
      }
    });
    if(c.parent<0){ laneOf[c.id]=alloc(c.id); }
    else{
      const pl=laneOf[c.parent];
      if(tip[pl]===c.parent){ laneOf[c.id]=pl; tip[pl]=c.id; }
      else{ laneOf[c.id]=alloc(c.id); }
    }
  });
  const laneCount=Math.max(...laneOf)+1;
  const chashToId=new Map(commits.map(c=>[c.chash,c.id]));
  const tipNames=new Map();
  branches.forEach((id,name)=>{ if(!tipNames.has(id)) tipNames.set(id,[]); tipNames.get(id).push({name,kind:'b'}); });
  tags.forEach((th,name)=>{ const t=POOL.get(th); const id=t? chashToId.get(t.commit0):null;
    if(id==null) return;
    if(!tipNames.has(id)) tipNames.set(id,[]); tipNames.get(id).push({name,kind:'t',th}); });
  tipNames.forEach(a=>a.sort((x,y)=>x.name<y.name?-1:1));
  const maxStack=Math.max(0,...[...tipNames.values()].map(a=>a.length));
  const XS=78, Y0=30+maxStack*16, YS=40, R=6.5;
  const rOf=c=>4.5+Math.min(4, Math.sqrt(c.written.size||0)); // radius = chunks written
  const W=24+commits.length*XS, H=Y0+(laneCount-1)*YS+34;
  const onpath=new Set(); for(let i=selected;i>=0;i=commits[i].parent) onpath.add(i);
  const svg=d3.select(s).append('svg').attr('width',W).attr('height',H);
  const X=c=>24+c.id*XS, Y=c=>Y0+laneOf[c.id]*YS;
  const edgeData=[];
  commits.forEach(c=>{
    if(c.parent>=0) edgeData.push({c, p:c.parent, second:false});
    if(c.parent2!=null && c.parent2>=0) edgeData.push({c, p:c.parent2, second:true});
  });
  svg.selectAll('path.cedge').data(edgeData).join('path')
    .attr('class',d=>'cedge'+(d.second?' second':'')
      +((onpath.has(d.c.id)&&onpath.has(d.p))?' onpath':''))
    .attr('d',d=>{
      const pr=commits[d.p];
      const r1=rOf(pr)+1.5, r2=rOf(d.c)+1.5;
      const x1=X(pr), y1=Y(pr), x2=X(d.c), y2=Y(d.c);
      if(y1===y2) return `M${x1+r1},${y1}L${x2-r2},${y2}`;
      if(d.second){
        // merge-in: run along the source lane, curve into the merge at the end
        const xb=Math.max(x1+r1+6, x2-XS+22);
        return `M${x1+r1},${y1}L${xb},${y1}C${x2-14},${y1} ${x2-r2-16},${y2} ${x2-r2},${y2}`;
      }
      // branch-out: curve into the child's lane right after the parent, then run
      const xa=Math.min(x2-r2-6, x1+XS-22);
      return `M${x1+r1},${y1}C${x1+28},${y1} ${x1+16},${y2} ${xa},${y2}L${x2-r2},${y2}`;
    });
  const g=svg.selectAll('g.cnode').data(commits).join('g')
    .attr('class',c=>'cnode'+(c.id===selected?' sel':'')+(c.pruned?' pruned':'')+(onpath.has(c.id)?' onpath':'')
      +(diffBase===c.id?' diffbase':''))
    .attr('transform',c=>`translate(${X(c)},${Y(c)})`)
    .attr('data-id',c=>c.id).attr('data-lane',c=>laneOf[c.id]).attr('data-chash',c=>c.chash||'')
    .on('click',(ev,c)=>{
      if(c.pruned){
        el('simnarr').innerHTML=`<b>${cshort(c.id)} ${c.label}</b> is pruned — its chunks were swept by garbage collection; there is nothing left to read.`;
        return;
      }
      if(ev.shiftKey){
        diffBase = diffBase===c.id? null : c.id;
        renderStrip(); renderTrees(); renderRibbon();
        if(diffBase!=null && diffBase!==selected){ startDiffTrace(commits[diffBase], cur()); }
        else { cancelReadAnim(); el('simnarr').innerHTML='diff base unpinned — panes are back to parent-vs-selected.'; }
        return;
      }
      selected=c.id; selectedKey=null; pickedChunk=null;
      const tipOf=[...branches.entries()].filter(([,id])=>id===c.id).map(([n])=>n).sort();
      currentBranch=tipOf.length? tipOf[0] : null;
      renderAll();
      el('simnarr').innerHTML= currentBranch
        ? `checked out <b>${currentBranch}</b> at ${cshort(c.id)}.`
        : `viewing <b>${cshort(c.id)} ${c.label}</b> detached — a write now branches from this commit; name it with <i>Branch here</i> to keep it findable.`;
    });
  g.append('circle').attr('r',c=>rOf(c));
  const glyph=c=> c.rootHash==null? '∅'
    : c.label.startsWith('insert')? '+'
    : c.label.startsWith('update')? '~'
    : c.label.startsWith('delete')? '−'
    : c.label.startsWith('batch')? '≡'
    : c.label.startsWith('merge')? '⋈' : '';
  g.append('text').attr('class','cglyph')
    .attr('y',3).attr('text-anchor','middle').text(c=>glyph(c));
  g.append('text').attr('class','cid').attr('y',-11).attr('text-anchor','middle')
    .text(c=>c.chash? c.chash.slice(0,8) : '#'+c.id);
  g.append('text').attr('class','clabel').attr('y',19).attr('text-anchor','middle')
    .text(c=>c.label.length>12? c.label.slice(0,11)+'…' : c.label);
  g.append('title').text(c=>`${cshort(c.id)} ${c.label}\ncommit ⋄${c.chash? c.chash.slice(0,12):'?'}\nroot ${c.rootHash? c.rootHash.slice(0,12):'∅'} · wrote ${c.written.size} chunks`+
    (c.parent>=0?`\nparent ${cshort(c.parent)}`:'\ngenesis')+
    (c.pruned?'\nPRUNED by garbage collection':''));
  // branch chips above their tip nodes — the manifest, drawn
  const chips=[];
  tipNames.forEach((names,id)=>names.forEach((n,i)=>chips.push({...n,id,stack:i})));
  const chipW=d=>16+d.name.length*5.6+(d.kind==='t'?6:0);
  const cg=svg.selectAll('g.bchip').data(chips).join('g')
    .attr('class',d=>'bchip'+(d.kind==='t'?' tag':'')+(d.kind==='b'&&d.name===currentBranch?' cur':''))
    .attr('data-branch',d=>d.kind==='b'? d.name:null)
    .attr('data-tag',d=>d.kind==='t'? d.name:null)
    .attr('data-commit',d=>d.id)
    .attr('transform',d=>{
      const x=Math.max(chipW(d)/2+2, X(commits[d.id]));
      return `translate(${x},${Y(commits[d.id])-24-d.stack*16})`; })
    .on('click',(ev,d)=>{
      if(d.kind==='b'){ checkout(d.name); return; }
      selected=d.id; selectedKey=null; pickedChunk=null; currentBranch=null; renderAll();
      el('simnarr').innerHTML=`tag <b>${d.name}</b> → ${cshort(d.id)} (detached view) — a tag cannot be checked out for writing; it only pins.`;
    });
  cg.append('rect').attr('x',d=>-chipW(d)/2).attr('y',-9).attr('width',chipW).attr('height',14).attr('rx',7);
  cg.append('text').attr('class','bname').attr('x',d=>-chipW(d)/2+6).attr('y',2)
    .text(d=>(d.kind==='t'?'⊙':'')+d.name);
  const bx=cg.append('g').attr('class','bx')
    .attr('transform',d=>`translate(${chipW(d)/2-8},0)`)
    .on('click',(ev,d)=>{ ev.stopPropagation();
      if(d.kind==='b'){ deleteBranch(d.name); return; }
      tags.delete(d.name); renderAll();
      el('simnarr').innerHTML=`tag <b>${d.name}</b> deleted — the NAME is gone; the tag object itself lingers in the store until a sweep no longer finds it.`;
    });
  bx.append('rect').attr('x',-5).attr('y',-7).attr('width',11).attr('height',12).attr('fill','transparent');
  bx.append('text').attr('y',2).attr('text-anchor','middle').text('×');
  cg.append('title').text(d=> d.kind==='t'
    ? `tag ${d.name} ⊙ pins ${cshort(d.id)} — immutable, content-addressed, survives every sweep · × deletes the name`
    : `branch ${d.name} → ${cshort(d.id)}`+(d.name===currentBranch?' (checked out)':'')+
      ' — click to check out · × deletes the ref (never the chunks)');
  s.scrollLeft=s.scrollWidth;
}

function renderBranchBar(){
  const cb=el('curbranch');
  if(currentBranch!=null){ cb.textContent=`on ${currentBranch}`; cb.className='bcur'; }
  else { cb.textContent=commits.length? `detached @ ${cshort(selected)}`:''; cb.className='bcur detached'; }
  const others=[...branches.keys()].filter(n=>n!==currentBranch).sort();
  const ms=el('mergesel');
  ms.innerHTML=others.map(n=>`<option value="${n}">${n}</option>`).join('');
  ms.disabled = !currentBranch || !others.length;
  el('mergebtn').disabled = !currentBranch || !others.length;
}

function checkout(name){
  currentBranch=name; selected=branches.get(name); selectedKey=null; pickedChunk=null;
  renderAll();
  el('simnarr').innerHTML=`checked out <b>${name}</b> at ${cshort(selected)} — reads and the next write now follow this ref.`;
}

function deleteBranch(name){
  if(name===currentBranch){
    el('simnarr').innerHTML=`<b>branch '${name}'</b> is checked out — check out another ref (click its chip) before deleting this one.`;
    return;
  }
  branches.delete(name);
  renderAll();
  el('simnarr').innerHTML=`<b>branch '${name}'</b> deleted — a ref removal touches zero chunks; its commits survive until a reachability sweep no longer finds them from any retained root.`;
}

/** The ribbon's overview bar: one segment per leaf (flex-grow = key count),
    same status colors as the panes, a viewport window mirroring the detail
    row's scroll, and click/drag navigation. The window updates on scroll +
    resize; segments click through to their chunkbox. */
function renderRibbonMap(leaves, isNew){
  const map=el('ribbonmap');
  if(leaves.length<2){ map.hidden=true; return; }
  map.hidden=false;
  const win=el('ribbonmapwin');
  map.querySelectorAll('.mseg').forEach(s=>s.remove());
  leaves.forEach(n=>{
    const s=document.createElement('div');
    s.className='mseg'+(isNew(n)?' new':'');
    s.style.flexGrow=n.count;
    s.dataset.hash=n.hash;
    s.title=`${n.span[0]}‥${n.span[1]} · ${n.count} keys · ${isNew(n)?'written by this commit':'shared'}`;
    map.insertBefore(s,win);
  });
  syncRibbonMapWindow();
}
function syncRibbonMapWindow(){
  const rb=el('ribbon'), win=el('ribbonmapwin');
  if(!rb || !win) return;
  const overflow=rb.scrollWidth - rb.clientWidth;
  win.hidden = overflow<=1;
  if(win.hidden) return;
  win.style.left =(100*rb.scrollLeft/rb.scrollWidth)+'%';
  win.style.width=(100*rb.clientWidth/rb.scrollWidth)+'%';
}
function wireMiniMapNav(map, scroller, sync){
  scroller.addEventListener('scroll', sync, {passive:true});
  addEventListener('resize', sync);
  const scrollTo=ev=>{
    const r=map.getBoundingClientRect();
    const frac=Math.min(Math.max((ev.clientX-r.left)/r.width,0),1);
    scroller.scrollLeft = frac*scroller.scrollWidth - scroller.clientWidth/2;
  };
  map.addEventListener('pointerdown',ev=>{
    map.setPointerCapture(ev.pointerId);
    scrollTo(ev);
    const move=e2=>scrollTo(e2);
    map.addEventListener('pointermove',move);
    map.addEventListener('pointerup',()=>map.removeEventListener('pointermove',move),{once:true});
  });
}
function syncLensMapWindow(){
  const sc=el('lensscroll'), win=el('lensmapwin');
  if(!sc || !win) return;
  const overflow=sc.scrollWidth - sc.clientWidth;
  win.hidden = overflow<=1;
  if(win.hidden) return;
  win.style.left =(100*sc.scrollLeft/sc.scrollWidth)+'%';
  win.style.width=(100*sc.clientWidth/sc.scrollWidth)+'%';
}
{ // one-time wiring — the elements persist; only their contents re-render
  if(el('ribbon') && el('ribbonmap')) wireMiniMapNav(el('ribbonmap'), el('ribbon'), syncRibbonMapWindow);
  if(el('lensscroll') && el('lensmap')) wireMiniMapNav(el('lensmap'), el('lensscroll'), syncLensMapWindow);
}

/** Scroll the lens (at L0) so key k's bytes are in view — shared by the
    inspector's locate button and the ribbon's chunk-hash tags. */
function locateInLens(k){
  if(lensLevel!==0){ lensLevel=0; renderLens(); }
  const lx=lensKeyX.get(k);
  if(lx!=null) el('lensscroll').scrollTo({left:Math.max(0,lx-120), behavior:'smooth'});
}

/** Armed by the ⚡ what-if button: the next lens-byte click runs the
    counterfactual replay instead of inspecting the chunk. */
let lensPerturbArmed=false;

function renderLens(){
  const c=cur(); const svg=d3.select('#lens'); svg.selectAll('*').remove();
  const cap=el('lenscaption'), rd=el('lensreadout'), lv=el('lenslevels');
  lv.innerHTML='';
  if(!c.entries.length){
    svg.attr('width',10).attr('height',10);
    cap.textContent='empty tree — no bytes, no boundaries.'; rd.textContent=''; return;
  }
  const rootLevel=POOL.get(c.rootHash).level;
  if(lensLevel>rootLevel) lensLevel=0;
  // level selector: the SAME splitter groups every level; L0 chunks entries into
  // leaves, Lk chunks (maxKey:childHash) items into level-k internal nodes
  for(let k=0;k<=rootLevel;k++){
    const b=document.createElement('button');
    b.textContent='L'+k; b.className=k===lensLevel?'cur':'';
    b.title=k===0?'leaf grouping: entries → leaf chunks':`internal grouping: level-${k-1} items → L${k} nodes`;
    b.onclick=()=>{ lensLevel=k; renderLens(); };
    lv.appendChild(b);
  }
  const pb=document.createElement('button');
  pb.id='lensperturb'; pb.textContent='⚡ what-if';
  pb.className=lensPerturbArmed?'warm':'';
  pb.title='arm, then click any byte: the SAME stream replays with that one byte changed — '+
    'see which boundaries hold, which move, and where the stream resyncs '+
    "(content-defined chunking's locality, live)";
  pb.onclick=()=>{ lensPerturbArmed=!lensPerturbArmed; renderLens(); };
  lv.appendChild(pb);
  const items=lensStream(c.rootHash, c.entries, lensLevel);
  const L=lensReplay(items, c.cfg.avg, c.cfg.seed, lensLevel);
  L.cells.forEach((d,i)=>d.i=i);
  const levelNodes=lensLevelNodes(c.rootHash, lensLevel);
  const p=c.parent>=0? commits[c.parent]:null;
  let prevBounds=new Set();
  if(p && p.entries.length && p.rootHash && POOL.has(p.rootHash)){
    const pRoot=POOL.get(p.rootHash).level;
    if(lensLevel<=pRoot){
      prevBounds=new Set(lensReplay(lensStream(p.rootHash,p.entries,lensLevel), p.cfg.avg, p.cfg.seed, lensLevel).boundKeys);
    }
  }
  const nowBounds=new Set(L.boundKeys);
  const CW=9, hMax=96, base=128, H=174; // +16: the chunk-length ruler row
  const W=L.cells.length*CW+26;
  svg.attr('width',W).attr('height',H).attr('viewBox',`0 0 ${W} ${H}`);
  const g=svg.append('g').attr('transform','translate(10,0)');
  // chunk-membership bands, colored by whether THIS commit wrote that chunk
  const chunkExt=new Map();
  L.cells.forEach(d=>{ const e=chunkExt.get(d.chunk)||{s:d.i,e:d.i}; e.s=Math.min(e.s,d.i); e.e=Math.max(e.e,d.i); chunkExt.set(d.chunk,e); });
  g.selectAll('rect.chunkband').data([...chunkExt.entries()]).join('rect')
    .attr('class',([ci])=>{ const nd=levelNodes[ci];
      return 'chunkband '+(nd && c.written.has(nd.hash)? 'bnew' : ci%2? 'odd':'even'); })
    .attr('x',([,e])=>e.s*CW-1).attr('width',([,e])=>(e.e-e.s+1)*CW+1)
    .attr('y',8).attr('height',base+18);
  // lens minimap: one segment per chunk, width ∝ bytes, amber = written
  {
    const map=el('lensmap'), win=el('lensmapwin');
    map.hidden = chunkExt.size<2;
    map.querySelectorAll('.mseg').forEach(s=>s.remove());
    if(!map.hidden){
      [...chunkExt.entries()].forEach(([ci,e])=>{
        const nd=levelNodes[ci];
        const s=document.createElement('div');
        s.className='mseg'+(nd && c.written.has(nd.hash)?' new':'');
        s.style.flexGrow=e.e-e.s+1;
        s.title=`chunk ${ci} · ${e.e-e.s+1} B`+(nd?` · ${nd.span[0]}‥${nd.span[1]}`:'');
        map.insertBefore(s,win);
      });
      requestAnimationFrame(syncLensMapWindow);
    }
  }
  // chunk-length ruler: measured bytes per chunk + the geometric expectation tick
  g.selectAll('text.clen').data([...chunkExt.entries()].filter(([,e])=>(e.e-e.s+1)*CW>34)).join('text')
    .attr('class','clen')
    .attr('x',([,e])=>((e.s+e.e+1)/2)*CW).attr('y',H-4).attr('text-anchor','middle')
    .text(([,e])=>`${e.e-e.s+1} B`)
    .append('title').text(`measured chunk length — expected ≈ ${10+L.T} B (min-zone 10 + mean wait T)`);
  g.selectAll('line.cexp').data([...chunkExt.entries()].filter(([,e])=>e.s+10+L.T<=e.e)).join('line')
    .attr('class','cexp')
    .attr('x1',([,e])=>(e.s+10+L.T)*CW).attr('x2',([,e])=>(e.s+10+L.T)*CW)
    .attr('y1',H-14).attr('y2',H-2)
    .attr('stroke','var(--faint)').attr('stroke-dasharray','2 2').attr('stroke-width',1)
    .append('title').text(`expected boundary ≈ here (${10+L.T} B in) — this chunk ran longer`);
  // min-zone bands
  const bands=[]; let run=null;
  L.cells.forEach((d,i)=>{ if(d.tag==='min'){ if(!run) run={s:i}; run.e=i; } else if(run){ bands.push(run); run=null; } });
  if(run) bands.push(run);
  g.selectAll('rect.minband').data(bands).join('rect').attr('class','minband')
    .attr('x',d=>d.s*CW-1).attr('width',d=>(d.e-d.s+1)*CW+1)
    .attr('y',base-4-hMax-6).attr('height',hMax+10);
  g.selectAll('text.minlabel').data(bands.filter(d=>d.e-d.s>3)).join('text').attr('class','minlabel')
    .attr('x',d=>((d.s+d.e+1)/2)*CW).attr('y',base-hMax-14).attr('text-anchor','middle').text('min-zone');
  // per-byte heat bars
  const heat=d=>{ const raw=Math.max(1-(d.mod/d.T),0.06); return d.tag==='min'? raw*0.45 : raw; };
  const idleText=`T=${L.T}: a trigger needs h%${L.T}==0, suppressed for the first 10 bytes of a chunk → expected chunk ≈ ${10+L.T} bytes · hover a bar for the live hash · click it to inspect its chunk`;
  rd.replaceChildren(Object.assign(document.createElement('span'),{className:'lr-win',textContent:idleText}));
  rd.title=idleText;
  const winText=d=>{ const lo=d.i-Math.min(WIN,d.since)+1;
    return L.cells.slice(lo,d.i+1).map(e=>e.ch).join(''); };
  g.selectAll('rect.cell').data(L.cells).join('rect').attr('class','cell')
    .attr('x',d=>d.i*CW).attr('width',CW-1.4)
    .attr('y',d=>base-4-hMax*heat(d)).attr('height',d=>hMax*heat(d))
    .attr('fill',d=> d.tag==='min'? 'var(--mincell)'
                   : d.tag==='fired'? 'var(--new)'
                   : d.tag==='forced'? 'var(--orph)'
                   : d.tag==='latched'? 'var(--latched)'
                   : 'color-mix(in srgb,var(--new) 34%, var(--lensedge))')
    .on('mouseover',(ev,d)=>{
      const lo=d.i-Math.min(WIN,d.since)+1;
      g.selectAll('rect.cell').classed('inwin',e=> e.i>=lo && e.i<=d.i);
      // cross-view: light this byte's chunk in the tree pane (+ ribbon at L0)
      const nd0=levelNodes[d.chunk];
      if(nd0){
        const tn=document.querySelector(`#aftersvg g.nd[data-hash="${nd0.hash}"]`);
        if(tn) tn.classList.add('hoverlink');
        const cb=document.querySelector(`#ribbon .chunkbox[data-hash="${nd0.hash}"]`);
        if(cb) cb.classList.add('hoverlink');
      }
      const st= d.tag==='min'?'min-zone (suppressed)':d.tag==='fired'?'TRIGGER — boundary latched'
              : d.tag==='forced'?'cap forced':d.tag==='latched'?'latched (closes at item end)':'rolling';
      rd.innerHTML=`<span class="lr-fix">byte ${d.i} '${d.ch}'</span>`+
        `<span class="lr-fix">key ${d.k}</span>`+
        `<span class="lr-fix">chunk ${d.chunk}</span>`+
        `<span class="lr-fix">h%${d.T} = <b>${d.mod}</b> · ${st}</span>`+
        `<span class="lr-win">window ← "${winText(d)}"</span>`;
      rd.title=`byte ${d.i} '${d.ch}' · key ${d.k} · chunk ${d.chunk} · h%${d.T} = ${d.mod} · ${st} · window ← "${winText(d)}"`;
    })
    .on('mouseout',()=>{ g.selectAll('rect.cell').classed('inwin',false);
      document.querySelectorAll('#aftersvg g.nd.hoverlink,#ribbon .chunkbox.hoverlink')
        .forEach(x=>x.classList.remove('hoverlink'));
      rd.replaceChildren(Object.assign(document.createElement('span'),{className:'lr-win',textContent:idleText}));
      rd.title=idleText; })
    .on('click',(ev,d)=>{
      if(lensPerturbArmed){ drawCounterfactual(d); return; }
      const nd=levelNodes[d.chunk]; if(!nd) return;
      pickedChunk=nd.hash; renderTrees(); renderStore(); inspect(nd.hash);
    });
  /** The ⚡ what-if overlay: replay with byte d.i changed, diff the boundary
      sets, tint the affected span, and narrate the resync — the locality of
      content-defined chunking, demonstrated on one byte. */
  function drawCounterfactual(d){
    const cf=lensCounterfactual(items, c.cfg.avg, c.cfg.seed, lensLevel, d.i);
    if(!cf) return;
    g.select('g.cfx').remove();
    const grp=g.append('g').attr('class','cfx');
    const cfB=new Set(cf.boundKeys);
    const held=[...nowBounds].filter(k=>cfB.has(k));
    const gone=[...nowBounds].filter(k=>!cfB.has(k));
    const appeared=[...cfB].filter(k=>!nowBounds.has(k));
    // resync = the first SURVIVING boundary at/after the mutated byte
    const resync=held.map(k=>({k,x:entryEnd(k)})).filter(m=>m.x>=d.i*CW).sort((a,b)=>a.x-b.x)[0];
    const zoneEnd=resync? resync.x : L.cells.length*CW;
    grp.append('rect').attr('class','cfzone')
      .attr('x',d.i*CW).attr('y',8).attr('width',Math.max(zoneEnd-d.i*CW,CW)).attr('height',base+18);
    const mark=(keys,dash,glyph)=>{
      const mg=grp.selectAll(null).data(keys.map(k=>({k,x:entryEnd(k)}))).join('g')
        .attr('transform',m=>`translate(${m.x},0)`);
      mg.append('line').attr('y1',12).attr('y2',base+24).attr('stroke-width',2)
        .attr('stroke-dasharray',dash);
      mg.append('text').attr('y',9).attr('x',3).attr('font-size',10).text(glyph);
      mg.append('title').text(m=>`what-if: boundary after key ${m.k} ${glyph==='+'?'APPEARS':'VANISHES'} under the one-byte change`);
    };
    mark(appeared,null,'+');
    mark(gone,'4 3','×');
    cap.innerHTML=`<b style="color:var(--shared)">what-if</b>: byte ${d.i} '${cf.mut.from}'→'${cf.mut.to}' — `+
      `${held.length} boundaries HELD, <b style="color:var(--shared)">${gone.length} vanished, ${appeared.length} appeared</b>; `+
      (resync? `resynced at the boundary after key ${resync.k} (${Math.round((zoneEnd-d.i*CW)/CW)} B downstream)`
             : `no resync before stream end`)+
      ` — one changed byte moves boundaries only inside the tinted span; everything before it is untouched (hashing is causal), everything after the resync is byte-identical. Click another byte to retry · disarm ⚡ to clear.`;
  }
  // the byte stream itself
  const isTouched=k=> c.touchedKey===k || (c.touchedKeys && c.touchedKeys.has(k));
  g.selectAll('text.bchar').data(L.cells).join('text')
    .attr('class',d=>'bchar'+(isTouched(d.k)?' touched':''))
    .attr('x',d=>d.i*CW+(CW-1.4)/2).attr('y',base+9).attr('text-anchor','middle')
    .text(d=>d.ch);
  // item separators + key labels
  const starts=L.cells.filter(d=>d.first);
  if(lensLevel===0) lensKeyX=new Map(starts.map(d=>[d.k,d.i*CW]));
  g.selectAll('line.est').data(starts).join('line')
    .attr('x1',d=>d.i*CW-0.7).attr('x2',d=>d.i*CW-0.7).attr('y1',base-4-hMax-6).attr('y2',base+12)
    .attr('stroke','var(--edge2)').attr('stroke-width',0.9);
  g.selectAll('text.kl').data(starts).join('text')
    .attr('class',d=>'kl'+(isTouched(d.k)?' touched':''))
    .attr('x',d=>d.i*CW+2).attr('y',base+23).text(d=>d.k);
  // boundary ticks: kept/born amber, died = rust hollow
  const entryEnd=k=>{ let last=-1; L.cells.forEach(d=>{ if(d.k===k) last=d.i; }); return (last+1)*CW; };
  const validKeys=new Set(items.map(it=>it.k));
  const marks=[...new Set([...nowBounds,...prevBounds])].filter(k=>validKeys.has(k)).map(k=>({
    k,x:entryEnd(k), kind: nowBounds.has(k)? (prevBounds.has(k)?'kept':'born') : 'died'}));
  const bt=g.selectAll('g.btick').data(marks).join('g').attr('class','btick')
    .attr('transform',d=>`translate(${d.x},0)`);
  bt.append('line').attr('y1',12).attr('y2',base+24)
    .attr('stroke',d=>d.kind==='died'?'var(--orph)':'var(--new)')
    .attr('stroke-width',d=>d.kind==='kept'?1.6:2.4)
    .attr('stroke-dasharray',d=>d.kind==='died'?'4 3':null);
  bt.append('text').attr('y',9).attr('x',-4).attr('font-size',10)
    .attr('fill',d=>d.kind==='died'?'var(--orph)':'var(--new)')
    .text(d=>d.kind==='born'?'+':d.kind==='died'?'×':'');
  bt.append('title').text(d=>`boundary after key ${d.k}: ${d.kind}`+
    (d.kind==='died'?' — this commit removed it (chunks merged)':d.kind==='born'?' — new this commit (chunk split)':' — unchanged, chunk resyncs here'));
  const born=marks.filter(m=>m.kind==='born').length, died=marks.filter(m=>m.kind==='died').length;
  cap.innerHTML=(lensLevel===0
      ? `the row of characters IS the serialized leaf stream; each bar is the rolling hash after that byte. `
      : `level ${lensLevel}: the SAME splitter now rolls over <code>(maxKey:childHash);</code> items of the L${lensLevel-1} nodes — `+
        `the recursion is literal, and because child hashes feed the window, a rebuilt child can move boundaries up here. `)+
    `An amber bar hits the trigger and latches a boundary, which closes the chunk at that ${lensLevel===0?'entry':'item'}'s end. `+
    `Hover any byte: the highlighted cells are its ${WIN}-byte window — the ONLY bytes the decision can see. `+
    `vs parent: <b style="color:var(--new)">${born} born</b>, <b style="color:var(--orph)">${died} died</b>, ${marks.length-born-died} kept.`;
}

function renderStore(){
  const bands=el('storebands'); bands.innerHTML='';
  const live=reach(cur().rootHash);
  const byBirth=new Map();
  for(const [h,n] of POOL){ const b=n.bornAt??0; if(!byBirth.has(b)) byBirth.set(b,[]); byBirth.get(b).push(n); }
  commits.forEach(c=>{
    const band=document.createElement('div'); band.className='storeband'+(c.pruned?' pruned':'');
    const bl=document.createElement('span'); bl.className='bl';
    bl.textContent=`${cshort(c.id)} ${c.label}`+(c.pruned?' · pruned':'');
    band.appendChild(bl);
    const bt=document.createElement('div'); bt.className='bt';
    const mine=(byBirth.get(c.id)||[]).sort((a,b)=>
      (b.commit?1:0)-(a.commit?1:0) || (b.level??0)-(a.level??0) || (a.span?a.span[0]:0)-(b.span?b.span[0]:0));
    if(!mine.length && !c.written.size){
      const z=document.createElement('span'); z.className='hint'; z.textContent='∅ wrote nothing'; bt.appendChild(z);
    }
    mine.forEach(n=>{
      const t=document.createElement('button');
      if(n.tag){
        t.className='chunk ctag'+(pickedChunk===n.hash?' picked':'')+(badHashes.has(n.hash)?' cbad':'');
        t.dataset.hash=n.hash;
        t.innerHTML='⊙'+n.name;
        t.title=`TAG OBJECT ${n.hash} — immutable pin '${n.name}' → commit ⋄${n.commit0.slice(0,10)}`;
        t.onclick=()=>{ pickedChunk=n.hash; inspect(n.hash); renderTrees(); renderStore(); };
        bt.appendChild(t); return;
      }
      if(n.commit){
        t.className='chunk ccommit'+(c.id===selected?' cfresh':'')+(pickedChunk===n.hash?' picked':'');
        t.dataset.hash=n.hash;
        t.innerHTML='⋄'+n.hash.slice(0,6);
        t.title=`COMMIT OBJECT ${n.hash} — root + parent hashes + message, content-addressed like every other chunk. `+
          `Its hash seals the whole history below it (Merkle).`;
        t.onclick=()=>{ pickedChunk=n.hash; inspect(n.hash); renderTrees(); renderStore(); };
        bt.appendChild(t); return;
      }
      const st = cur().written.has(n.hash)? 'snew' : live.has(n.hash)? 'slive' : 'shist';
      t.className='chunk '+st+(pickedChunk===n.hash?' picked':'')+(badHashes.has(n.hash)?' cbad':'');
      t.dataset.hash=n.hash;
      t.innerHTML=n.hash.slice(0,6)+(n.level>0?`<sup>L${n.level}</sup>`:'');
      t.title=`${n.level===0?'leaf':'internal L'+n.level} ${n.hash} · span ${n.span[0]}‥${n.span[1]} · `+
        (st==='snew'?'written by the selected commit':st==='slive'?'live: reachable from the selected commit':'historic: unreachable from the selected commit (sweepable)');
      t.onclick=()=>{ pickedChunk=n.hash; inspect(n.hash); renderTrees(); renderStore(); };
      bt.appendChild(t);
    });
    const swept=c.written.size - mine.filter(n=>!n.commit).length;
    band._sweptCount=swept;
    if(swept>0 && c.written.size){ const chip=document.createElement('span'); chip.className='sweptchip';
      chip.textContent=`· ${swept} swept`; bt.appendChild(chip); }
    band.appendChild(bt);
    bands.appendChild(band);
  });
  const orphanWork=byBirth.get(-1)||[];
  if(orphanWork.length){
    const band=document.createElement('div'); band.className='storeband';
    const bl=document.createElement('span'); bl.className='bl';
    bl.textContent='(abandoned attempt — lost the race, never committed)';
    bl.style.color='var(--orph)';
    band.appendChild(bl);
    const bt=document.createElement('div'); bt.className='bt';
    orphanWork.forEach(n=>{
      const t=document.createElement('button');
      t.className='chunk shist'+(pickedChunk===n.hash?' picked':'')+(badHashes.has(n.hash)?' cbad':'');
      t.dataset.hash=n.hash;
      t.innerHTML=n.hash.slice(0,6)+(n.level>0?`<sup>L${n.level}</sup>`:'');
      t.title=`built by the losing writer, referenced by NOTHING — sweepable garbage (${n.hash})`;
      t.onclick=()=>{ pickedChunk=n.hash; inspect(n.hash); renderTrees(); renderStore(); };
      bt.appendChild(t);
    });
    band.appendChild(bt);
    bands.appendChild(band);
  }
}

function renderStats(){
  const c=cur();
  el('s-written').innerHTML=`${c.written.size}<span class="u"> / ${c.total}</span>`;
  // bytes: the real cost currency — preimage lengths, the serialized forms
  {
    let wb=0; c.written.forEach(h=>wb+=streamBytes(h));
    let tb=0; if(c.rootHash) for(const h of reach(c.rootHash)) tb+=streamBytes(h);
    const pct= tb? (100*wb/tb) : 0;
    el('s-bytes').innerHTML=`${fmtBytes(wb)}<span class="u"> of ${fmtBytes(tb)} tree (${pct? pct.toFixed(0):'0'}%)</span>`;
  }
  // the garbage gauge: what a sweep would delete RIGHT NOW under the chosen policy
  {
    const {keep}=computeKeepSet();
    let sweepable=0;
    for(const h of POOL.keys()) if(!keep.has(h)) sweepable++;
    el('s-gc').innerHTML=`${sweepable}<span class="u"> sweepable of ${POOL.size} stored</span>`;
  }
  const shared=c.total-c.written.size;
  el('s-shared').innerHTML=`${shared}<span class="u"> (${c.total?Math.round(100*shared/c.total):0}%)</span>`;
  el('s-height').innerHTML=`${c.height}<span class="u"> levels · ${c.entries.length} keys</span>`;
  el('s-dedup').innerHTML=`${POOL.size? (naiveBytes/POOL.size).toFixed(1):'–'}×<span class="u"> ${POOL.size} in store vs ${naiveBytes} naive</span>`;
  // chart
  const svg=d3.select('#chart'); svg.selectAll('*').remove();
  const W=svg.node().clientWidth||400, H=74, pad=4;
  const data=commits.slice(-34);
  const maxW=Math.max(...data.map(d=>d.written.size), cur().height+2);
  const bw=Math.min(16,(W-pad*2)/Math.max(data.length,1)-2);
  const x=i=>pad+i*((W-pad*2)/Math.max(data.length,1));
  const y=v=>H-6-(H-16)*(v/maxW);
  svg.attr('viewBox',`0 0 ${W} ${H}`);
  svg.selectAll('rect').data(data).join('rect')
    .attr('x',(d,i)=>x(i)).attr('width',Math.max(bw,3))
    .attr('y',d=>y(d.written.size)).attr('height',d=>H-6-y(d.written.size))
    .attr('rx',1.5)
    .attr('fill',(d)=> d.id===selected? 'var(--new)' : 'color-mix(in srgb,var(--new) 45%, transparent)')
    .append('title').text(d=>`${cshort(d.id)} ${d.label}: wrote ${d.written.size} (n=${d.entries.length}, height ${d.height})`);
  // the contrast series: total tree chunks per commit, on ITS OWN scale — the
  // tree climbs while the written bars stay flat (that contrast IS the claim)
  const maxT=Math.max(...data.map(d=>d.total),1);
  const yT=v=>H-6-(H-16)*(v/maxT);
  svg.append('path').attr('class','growth')
    .attr('d','M'+data.map((d,i)=>`${x(i)+Math.max(bw,3)/2},${yT(d.total)}`).join('L'))
    .attr('fill','none').attr('stroke','var(--mut)').attr('stroke-width',1.3).attr('opacity',.85);
  svg.append('text').attr('x',x(data.length-1)+2).attr('y',yT(data[data.length-1].total)-3)
    .attr('fill','var(--mut)').attr('font-size',9).attr('text-anchor','end').text('tree size');
  svg.append('line').attr('x1',pad).attr('x2',W-pad).attr('y1',y(cur().height)).attr('y2',y(cur().height))
    .attr('stroke','var(--shared)').attr('stroke-dasharray','5 4').attr('stroke-width',1.2).attr('opacity',.8);
  svg.append('text').attr('x',W-pad).attr('y',y(cur().height)-4).attr('text-anchor','end')
    .attr('fill','var(--shared)').attr('font-size',10).text(`height ${cur().height}`);
}

/* The SIM inspector is DECLARATIVE (write-path-explorer.bind.js, loaded just before
   this script): five one-item data-each sections in #siminspect — message / blame /
   tag / commit / node — and the builders below assign plain data to exactly one of
   them (the [] sections don't exist in the DOM, so shapes never collide). Events are
   delegated ONCE on the container: chips carrying data-key are blame time-travel,
   data-commit + data-inspect are node time-travel, data-hash is tree navigation,
   #ilocate carries its own hash + span. */
const simInsVm = Bind.mount(el('siminspect'), {
  msg: [{ t: 'click any chunk — a tree node, a ribbon box, a lens bar, or a store tile — and its identity, provenance, and contents land here.' }],
  blame: [], tag: [], commitObj: [], node: [],
});
function simInspectMsg(t){
  Object.assign(simInsVm, { msg:[{t}], blame:[], tag:[], commitObj:[], node:[] });
}
el('siminspect').addEventListener('click',(ev)=>{
  const loc=ev.target.closest('#ilocate');
  if(loc){ simLocateChunk(loc.dataset.hash, +loc.dataset.span0); return; }
  const link=ev.target.closest('.ichip[data-hash]');
  if(link){ pickedChunk=link.dataset.hash; renderTrees(); renderStore(); inspect(link.dataset.hash); return; }
  const chip=ev.target.closest('.ichip[data-commit]');
  if(!chip) return;
  selected=+chip.dataset.commit;
  const tipOf=[...branches.entries()].filter(([,x])=>x===selected).map(([nm])=>nm).sort();
  currentBranch=tipOf.length? tipOf[0] : null;
  if(chip.dataset.key!==undefined){ // blame chips time-travel KEEPING the key
    const keep=+chip.dataset.key;
    renderAll(); selectedKey=keep; syncButtons(); renderRibbon(); renderKeyBlame(keep);
  }else{ // node chips time-travel keeping the chunk inspected
    selectedKey=null;
    renderAll(); inspect(chip.dataset.inspect);
  }
});

/** Per-key history: walk the selected commit's first-parent ancestry and list
    every commit where this key's value changed — blame, in miniature. Works
    even after a sweep (commit entries are metadata, never swept). */
function renderKeyBlame(k){
  const rows=[]; let i=selected;
  const valOf=(cm)=>{ const e2=cm.entries.find(x=>x.k===k); return e2? e2.v : undefined; };
  while(i>=0){
    const cm=commits[i]; const pr=cm.parent>=0? commits[cm.parent]:null;
    const v=valOf(cm), pv=pr? valOf(pr) : undefined;
    if(v!==pv) rows.push({id:cm.id, label:cm.label, from:pv, to:v});
    i=cm.parent;
  }
  const cv=cur().entries.find(e2=>e2.k===k);
  el('siminspect').hidden=false; el('realinspect').hidden=true; // the sim inspector reclaims the panel
  Object.assign(simInsVm, { msg:[], tag:[], commitObj:[], node:[], blame:[{
    head:`key ${k}`,
    curCls: cv? 'inew' : 'iorph',
    curTxt: cv? 'currently v'+cv.v : 'absent',
    at:` at ${cshort(selected)}`,
    histLine:`history along the first-parent line (${rows.length} change${rows.length===1?'':'s'}):`,
    rows: rows.map(r=>({ id:String(r.id), key:String(k), chip:cshort(r.id),
      disabled: commits[r.id].pruned || null,
      title: commits[r.id].pruned? 'pruned — unreadable' : null,
      label: r.label.slice(0,18),
      change: r.from===undefined? (r.to===undefined?'':'created v'+r.to)
            : r.to===undefined? `v${r.from} → deleted`
            : `v${r.from} → v${r.to}` })),
  }]});
}

function statusOfHash(hash){
  const c=cur();
  if(c.written.has(hash)) return 'new';
  return (c.rootHash && reach(c.rootHash).has(hash))? 'shared' : 'orph';
}

function inspect(hash){
  el('siminspect').hidden=false; el('realinspect').hidden=true; // the sim inspector reclaims the panel
  const n=POOL.get(hash);
  if(!n){ simInspectMsg('chunk swept — no longer in the store.'); return; }
  const c=cur();
  const one=(shape)=>Object.assign(simInsVm,{msg:[],blame:[],tag:[],commitObj:[],node:[],...shape});
  if(n.tag){
    one({ tag:[{ name:` · '${n.name}'`, hash,
      commit0:n.commit0, commitLabel:'⋄'+n.commit0.slice(0,10) }]});
    return;
  }
  if(n.commit){
    const owner=commits.find(cm=>cm.chash===hash);
    one({ commitObj:[{
      owner: owner? ` · ${cshort(owner.id)}` : '',
      hash,
      message:`“${n.message??n.label}”`,
      rootLinks: n.root? [{h:n.root, label:n.root.slice(0,10)}] : [],
      rootNone: n.root? '' : '∅ (empty tree)',
      parentsLead:`parent${n.parents.length===1?'':'s'}: `,
      parentLinks: n.parents.map(ph=>({h:ph, label:'⋄'+ph.slice(0,10)})),
      parentsNone: n.parents.length? '' : 'none — genesis',
    }]});
    return;
  }
  const status=statusOfHash(hash);
  const cls= status==='new'?'inew':status==='orph'?'iorph':'ishared';
  const stTxt= status==='new'?'WRITTEN this commit':status==='orph'?'NOT reachable from this commit':'SHARED by reference';
  // provenance: every live commit whose closure holds this chunk
  const aliveIn=commits.filter(cm=>!cm.pruned && cm.rootHash && reach(cm.rootHash).has(hash)).map(cm=>cm.id);
  // position in the SELECTED commit's tree
  let parent=null, depth=null;
  if(c.rootHash){
    (function walk(h,par,d){ const nd=POOL.get(h); if(!nd) return false;
      if(h===hash){ parent=par; depth=d; return true; }
      return nd.children? nd.children.some(ch=>walk(ch,h,d+1)) : false; })(c.rootHash,null,0);
  }
  // stream bytes + WHY the chunk closed — an exact isolated replay (the splitter
  // resets per chunk, so replaying just this chunk reproduces its decision)
  let bytes=0, closed=null;
  if(n.level===0){
    const sp=new Splitter(c.cfg.avg, buzTable(c.cfg.seed));
    let sawForced=false, sawFired=false;
    for(const e of n.entries){
      const str=entryBytes(e); bytes+=str.length;
      for(let i=0;i<str.length;i++){
        const t=sp.byte(str.charCodeAt(i));
        if(t==='forced') sawForced=true; if(t==='fired') sawFired=true;
      }
    }
    closed = sawFired? 'trigger fired' : sawForced? 'cap forced' : 'tail — stream ended before a trigger';
  } else {
    n.children.forEach((ch,i)=>{ bytes+=(`${(n.childKeys??[])[i]??'?'}:${ch}@${(n.counts??[])[i]??'?'};`).length; });
  }
  let rows;
  if(n.entries){
    rows=n.entries.map(e=>({ c1:`k ${e.k}`, link:[], c2:`= ${e.v}`,
      c3: e.k===n.span[1]? 'closes the chunk' : '' }));
  } else {
    const pfx=prefixCounts(n)||[];
    rows=n.children.map((h,i)=>{ const kid=POOL.get(h);
      return { c1: kid? (kid.level===0?'leaf':'L'+kid.level) : '?',
        link:[{h, label:h.slice(0,10)}], c2:'',
        c3:`${(n.counts??[])[i]??'?'} keys · ≤${pfx[i]??'?'} cum${kid? ' · '+kid.span[0]+'‥'+kid.span[1]:' · (swept — the COUNT survives: it lives in THIS node\'s bytes)'}` }; });
  }
  one({ node:[{
    kind: n.level===0? 'leaf' : 'internal L'+n.level,
    stCls: cls, stTxt,
    minted: n.bornAt!=null? [{id:String(n.bornAt), chunk:hash, label:cshort(n.bornAt)}] : [],
    hash,
    spanLine:`span ${n.span[0]}‥${n.span[1]} · ${n.count} keys · ${bytes} stream B`,
    closedBy: closed? [{t:closed}] : [],
    depthTail: depth!=null? ` · depth ${depth} in ${cshort(c.id)}` : '',
    aliveLead:`alive in ${aliveIn.length} commit${aliveIn.length===1?'':'s'}: `,
    alive: aliveIn.map(id=>({id:String(id), chunk:hash, label:cshort(id)})),
    parentLink: parent!=null? [{h:parent, label:parent.slice(0,10)+' ↑'}] : [],
    rootNote: (parent==null && depth===0)? [{t:`this chunk is the ROOT of ${cshort(c.id)}`}] : [],
    rows,
    locate: (n.level===0 && status!=='orph')? [{hash, span0:String(n.span[0])}] : [],
  }]});
}
/** The locate flash — driven by the delegated #ilocate click above. */
function simLocateChunk(hash, span0){
  const cb=document.querySelector(`#ribbon .chunkbox[data-hash="${hash}"]`);
  if(cb){ cb.scrollIntoView({behavior:'smooth',block:'nearest',inline:'center'});
    cb.classList.remove('flashit'); void cb.offsetWidth; cb.classList.add('flashit'); }
  locateInLens(span0);
  const tile=document.querySelector(`#storebands .chunk[data-hash="${hash}"]`);
  if(tile){ tile.scrollIntoView({behavior:'smooth',block:'nearest'});
    tile.classList.remove('flashit'); void tile.offsetWidth; tile.classList.add('flashit'); }
}

function narrate(c, prevReach){
  const shared=c.total-c.written.size;
  const orph=c.parent>=0? [...prevReach].filter(h=>!reach(c.rootHash).has(h)).length : 0;
  el('simnarr').innerHTML=
    `<b>${cshort(c.id)} ${c.label}</b> → wrote <b>${c.written.size}</b> of ${c.total} chunks `+
    `(<span class="sh">${shared} shared, ${c.total?Math.round(100*shared/c.total):0}%</span>)`+
    (c.parent>=0? (c.total===0
                    ? ` · orphaned ${orph} — the last key is gone, the tree is ∅; the old chunks await garbage collection.`
                    : ` · orphaned ${orph} · tree height ${c.height} over ${c.entries.length} keys — the amber set <i>is</i> the spine.`)
                : (c.total===0
                    ? ` — an empty tree writes nothing; the first insert will mint the first chunk.`
                    : ` — genesis build; every chunk is new exactly once.`));
}

function renderCfgHint(){
  if(!commits.length){ el('cfghint').textContent=''; return; }
  const cfg=cur().cfg;
  const differs = cfg.avg!==+el('avg').value || cfg.seed!==+el('seed').value;
  el('cfghint').textContent=`history pinned to avg ${cfg.avg} · seed ${cfg.seed}`+
    (differs? ' — controls differ; they apply on Rebuild only (the config is part of the format)':'');
}
/* ---------- JSON syntax highlighting (the pack modal) ----------
   Pure string → HTML-string. Escapes FIRST — a pack can carry arbitrary
   strings (a hand-edited file round-trips through import), so nothing goes
   into innerHTML unescaped — then wraps tokens in theme-aware spans:
   .tj-key object keys · .tj-str strings · .tj-num numbers · .tj-lit true/false/null */
function escHtml(s){ return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function highlightJson(src){
  return escHtml(src).replace(
    /("(?:[^"\\]|\\.)*")(\s*:)|("(?:[^"\\]|\\.)*")|(-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|\b(true|false|null)\b/g,
    (m,key,colon,str,num,lit)=>{
      if(key!==undefined) return `<span class="tj-key">${key}</span>${colon}`;
      if(str!==undefined) return `<span class="tj-str">${str}</span>`;
      if(num!==undefined) return `<span class="tj-num">${num}</span>`;
      return `<span class="tj-lit">${lit}</span>`;
    });
}

function renderAll(){ cancelReadAnim(); readPath=null; renderStrip(); renderBranchBar(); renderTrees(); renderRibbon(); renderLens(); renderStore(); renderStats(); renderHist(); renderCfgHint(); persistMeta(); }

